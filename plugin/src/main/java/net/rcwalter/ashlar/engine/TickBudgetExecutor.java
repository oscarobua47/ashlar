// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import com.google.gson.JsonElement;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcError;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single scheduler for {@link BuildTask}s, running on a
 * {@code runTaskTimer(plugin, this::tick, 1L, 1L)} main-thread tick.
 * Each tick works through the queue for at most {@code limits.tick-budget-ms}
 * of wall-clock time, resuming a partially-completed task on the next tick.
 * See plan.md &sect;2.1/&sect;2.3.
 *
 * <p>{@link #submit} may be called from any thread (in practice, a WebSocket
 * network thread) and only ever touches {@link #queue}, the one piece of
 * state shared across threads. Everything else here (the currently-running
 * task, its chunk-ticket guard) is only ever touched from {@link #tick()},
 * which Bukkit always invokes on the main thread.
 */
public final class TickBudgetExecutor {

    private record QueuedTask(BuildTask task, InvocationContext ctx, CompletableFuture<JsonElement> future, long submittedAtNanos) {
    }

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final Logger logger;
    private final ArrayDeque<QueuedTask> queue = new ArrayDeque<>();
    private final Object queueLock = new Object();

    private BukkitTask timerTask;

    // Main-thread-only state: only ever read/written from tick() and the private
    // helpers it calls, all of which run on the main thread.
    private QueuedTask current;
    private ChunkTicketGuard currentGuard;
    private long currentStartedAtNanos;

    public TickBudgetExecutor(JavaPlugin plugin, PluginConfig config, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
    }

    public void start() {
        this.timerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Cancels the tick timer and fails every task still queued or currently running with {@code INTERNAL}. */
    public void shutdown() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
        if (current != null) {
            releaseGuard();
            current.future().completeExceptionally(new RpcError(ErrorCode.INTERNAL, "plugin disabled"));
            current = null;
        }
        synchronized (queueLock) {
            QueuedTask qt;
            while ((qt = queue.pollFirst()) != null) {
                qt.future().completeExceptionally(new RpcError(ErrorCode.INTERNAL, "plugin disabled"));
            }
        }
    }

    /** Number of tasks queued, including the one currently running. Reported by {@code health}. */
    public int queuedCount() {
        synchronized (queueLock) {
            return queue.size() + (current != null ? 1 : 0);
        }
    }

    /**
     * Enqueues a task and wires up its progress events to flow through
     * {@code ctx.progress()}. Throws {@link RpcError} with {@link
     * ErrorCode#QUEUE_FULL} synchronously if the queue is already at {@code
     * limits.max-queued-operations}. Must not be called from the main thread
     * (see {@link MainThread#assertNotPrimary}) - the WebSocket network
     * thread satisfies this today; an in-JVM caller (the tool layer/agent,
     * plan.md step7) must too.
     */
    public CompletableFuture<JsonElement> submit(BuildTask task, InvocationContext ctx) {
        MainThread.assertNotPrimary("TickBudgetExecutor.submit");
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        task.setProgressListener(ctx.progress()::progress);
        synchronized (queueLock) {
            int max = config.limits().maxQueuedOperations();
            if (queue.size() >= max) {
                throw new RpcError(ErrorCode.QUEUE_FULL, "operation queue is full (" + max + " operations already queued)");
            }
            queue.addLast(new QueuedTask(task, ctx, future, System.nanoTime()));
        }
        return future;
    }

    private void tick() {
        long deadline = System.nanoTime() + config.limits().tickBudgetMs() * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (current == null && !startNext()) {
                return; // queue empty, nothing to do this tick
            }
            if (current.ctx().isCancelled()) {
                cancelCurrent();
                continue; // executor keeps running; try to pick up the next task within budget
            }
            boolean finished;
            try {
                finished = current.task().step(deadline);
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Build task threw during execution; aborting it", t);
                failCurrent(t);
                continue; // executor keeps running; try to pick up the next task within budget
            }
            if (finished) {
                completeCurrent();
                // Loop again: multiple small tasks may finish within one tick, but never past deadline.
            } else {
                return; // budget exhausted mid-task; resume on the next tick
            }
        }
    }

    /** Dequeues the next task and acquires its chunk tickets. Returns false if the queue is empty. */
    private boolean startNext() {
        QueuedTask next;
        synchronized (queueLock) {
            next = queue.pollFirst();
        }
        if (next == null) {
            return false;
        }
        current = next;
        currentStartedAtNanos = System.nanoTime();
        try {
            currentGuard = new ChunkTicketGuard(next.task().world(), next.task().region(), plugin);
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Failed to acquire chunk tickets for a build task; aborting it", t);
            failCurrent(t);
            return startNext();
        }
        return true;
    }

    private void completeCurrent() {
        QueuedTask qt = current;
        releaseGuard();
        current = null;
        // queuedMs: submit -> task actually starting. elapsedMs: execution time only
        // (plan.md 2.6 follow-up fix; previously elapsedMs included queue wait).
        long queuedMs = Math.max(0, (currentStartedAtNanos - qt.submittedAtNanos()) / 1_000_000L);
        long elapsedMs = Math.max(0, (System.nanoTime() - currentStartedAtNanos) / 1_000_000L);
        qt.task().reportCompletion();
        qt.future().complete(qt.task().buildResult(queuedMs, elapsedMs));
    }

    private void failCurrent(Throwable cause) {
        QueuedTask qt = current;
        releaseGuard();
        current = null;
        if (qt != null) {
            qt.future().completeExceptionally(new RpcError(ErrorCode.INTERNAL, "build task failed: " + cause.getMessage()));
        }
    }

    /**
     * Abandons the currently-running task because {@code ctx.isCancelled()}
     * returned true: releases its chunk tickets and completes its future
     * exceptionally with {@link ErrorCode#CANCELLED}, same as an internal
     * failure but without logging it as one. Nothing calls {@code
     * InvocationContext#cancel()} yet (the in-JVM agent will).
     */
    private void cancelCurrent() {
        QueuedTask qt = current;
        releaseGuard();
        current = null;
        qt.future().completeExceptionally(new RpcError(ErrorCode.CANCELLED, "operation cancelled"));
    }

    private void releaseGuard() {
        if (currentGuard != null) {
            currentGuard.release();
            currentGuard = null;
        }
    }
}
