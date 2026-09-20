// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.rpc;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The single allowed entry point from any off-main-thread code (the
 * WebSocket network threads, in particular) into the Bukkit/Paper API.
 *
 * <p>Every call into Bukkit (server version, player count, scheduler,
 * world/block access, etc.) MUST go through {@link #call(Supplier)}, which
 * schedules the work onto the main thread via
 * {@link org.bukkit.scheduler.BukkitScheduler#runTask} and completes a
 * {@link CompletableFuture} with the result. The only exception is
 * {@code plugin.getLogger()}, which is thread-safe and may be used anywhere.
 *
 * <p>{@link #call} completes its future from {@link #offMainExecutor}, not
 * from the main thread that produced the value (plan.md step7): a plain
 * {@code thenCompose}/{@code whenComplete} chained onto that future - the
 * pattern every handler that enqueues a task after a world-min/max-height
 * round trip uses, e.g. {@code MainThread.call(...).thenCompose(heights ->
 * startFill(...))} - runs on whichever thread calls {@code complete()}. Left
 * as a direct {@code future.complete(...)} inside the {@code runTask}
 * callback, that thread would be the main thread, so every such
 * continuation - including the {@code TickBudgetExecutor#submit}/service
 * call at the end of it - would silently run on the main thread too,
 * tripping {@link #assertNotPrimary} even for an ordinary WebSocket request
 * and, before that check existed, quietly contradicting "handlers run on
 * the network thread".
 */
public final class MainThread {

    private static volatile Plugin plugin;
    private static volatile Thread primaryThread;

    private static final ExecutorService offMainExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ashlar-offmain");
        t.setDaemon(true);
        return t;
    });

    private MainThread() {
    }

    /** Must be called once, from {@code onEnable} (on the main thread), before any RPC handler runs. */
    public static void init(Plugin owningPlugin) {
        plugin = owningPlugin;
        primaryThread = Thread.currentThread();
    }

    /**
     * Throws {@link IllegalStateException} if called from the main thread.
     * Every {@link cc.wujm.ashlar.engine.TickBudgetExecutor#submit} call
     * and every service entry point that blocks or enqueues calls this first,
     * so an in-process caller (the in-JVM tool layer/agent, plan.md step7)
     * can never run these from the tick thread it would itself be blocking.
     * A no-op before {@link #init} has run (e.g. in unit tests).
     */
    public static void assertNotPrimary(String what) {
        Thread main = primaryThread;
        if (main != null && Thread.currentThread() == main) {
            throw new IllegalStateException(what + " must not be called from the main thread");
        }
    }

    public static <T> CompletableFuture<T> call(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Plugin currentPlugin = plugin;
        if (currentPlugin == null || !currentPlugin.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("MainThread is not initialized or plugin is disabled"));
            return future;
        }
        Bukkit.getScheduler().runTask(currentPlugin, () -> {
            try {
                T result = task.get();
                offMainExecutor.execute(() -> future.complete(result));
            } catch (Throwable t) {
                offMainExecutor.execute(() -> future.completeExceptionally(t));
            }
        });
        return future;
    }
}
