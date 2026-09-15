// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import net.rcwalter.ashlar.agent.model.Usage;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the assistant loop for {@code agent.mode: embedded} (docs/private/prompts/
 * step8b-prompt.md): every {@code /ashlar} request (a real player's, or the console's via
 * {@code ashlar simulate}) is queued per player and executed on a virtual thread, capped by a
 * global concurrency semaphore - a pure-Java port of {@code mcp-server/src/agent/service.ts}'s
 * {@code startAgentService}. {@link #submit}, {@link #cancel} and {@link #admin} are the only
 * entry points meant to be called from the main thread (the command executor); they only enqueue
 * or flip a flag and never block. Everything else - the model calls and tool invocations - runs
 * on {@link #executor}, a virtual thread per request.
 */
public final class AgentService {

    private static final long PROGRESS_THROTTLE_MS = 1500;
    private static final int CHUNK_MAX_LENGTH = 1000;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 200;

    private record Pending(AgentRunner.PlayerInfo player, String text, AtomicBoolean cancelled) {
        Pending(AgentRunner.PlayerInfo player, String text) {
            this(player, text, new AtomicBoolean(false));
        }
    }

    /** One player's serial queue: at most one request running, the rest waiting in FIFO order. */
    private static final class PlayerQueue {
        Pending running;
        final Deque<Pending> queue = new ArrayDeque<>();
    }

    private final PluginConfig.AgentConfig config;
    private final AgentRunner runner;
    private final UsageStore usageStore;
    private final HistoryStore historyStore;
    private final Outbox outbox;
    private final AdminActions adminActions;
    private final Logger logger;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService progressScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ashlar-agent-progress");
        t.setDaemon(true);
        return t;
    });
    private final Semaphore concurrency;
    private final ConcurrentHashMap<UUID, PlayerQueue> playerQueues = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public AgentService(PluginConfig.AgentConfig config, ToolRegistry toolRegistry, ModelApi modelApi,
                         UsageStore usageStore, HistoryStore historyStore, Outbox outbox, Logger logger) {
        this.config = config;
        this.usageStore = usageStore;
        this.historyStore = historyStore;
        this.outbox = outbox;
        this.logger = logger;
        this.concurrency = new Semaphore(Math.max(1, config.limits().maxConcurrent()));

        Set<String> allowed = new LinkedHashSet<>(toolRegistry.names());
        if (!config.model().allowCommand()) {
            allowed.remove("mc_command");
        }
        Optional<String> systemPromptExtra = readSystemPromptExtra(config.model().systemPromptFile(), logger);
        this.runner = new AgentRunner(modelApi, toolRegistry, allowed, config.model().maxToolCalls(),
                config.model().imageDetail(), systemPromptExtra);

        this.adminActions = new AdminActions(usageStore,
                uuid -> cancelOutcome(UUID.fromString(uuid)),
                (uuid, text, kind) -> outbox.send(UUID.fromString(uuid), text, kind == AdminActions.SendKind.FINAL),
                config.pricing().currency());
    }

    private static Optional<String> readSystemPromptExtra(String path, Logger logger) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(Path.of(path)));
        } catch (IOException e) {
            logger.warning("[agent] agent.model.system-prompt-file could not be read (" + path + "): " + e.getMessage());
            return Optional.empty();
        }
    }

    // ---- main-thread entry points: enqueue/flip a flag only, never block ----

    /** Enqueues a request for {@code player}, or rejects it immediately (paused/over a daily limit). */
    public void submit(AgentRunner.PlayerInfo player, String text) {
        UUID uuid = UUID.fromString(player.uuid());
        if (closed.get()) {
            outbox.send(uuid, "The assistant is shutting down.", true);
            return;
        }
        if (usageStore.isPaused()) {
            outbox.send(uuid, "The assistant is paused by an operator.", true);
            return;
        }
        UsageStore.CheckResult check = usageStore.checkAllowed(player.uuid());
        if (!check.ok()) {
            outbox.send(uuid, check.reason(), true);
            return;
        }

        Pending pending = new Pending(player, text);
        PlayerQueue pq = playerQueues.computeIfAbsent(uuid, u -> new PlayerQueue());
        boolean startNow;
        synchronized (pq) {
            if (pq.running != null) {
                pq.queue.addLast(pending);
                startNow = false;
            } else {
                pq.running = pending;
                startNow = true;
            }
        }
        if (!startNow) {
            outbox.send(uuid, "Queued behind your previous request.", false);
            return;
        }
        executor.execute(() -> runPending(uuid, pq, pending));
    }

    /**
     * Cancels {@code uuid}'s own running request, if any, and drops everything still queued
     * behind it - a player's "/ashlar cancel" clears their whole backlog, not just the request in
     * flight (a deliberate step8b-prompt.md deviation from {@code service.ts}'s {@code
     * cancelPlayerRequest}, which only ever touches one of the two: this class's per-player queue
     * is entered and left atomically under one lock, so "running is null but the queue is
     * non-empty" - the state the TS version's separate {@code "queued"} branch handles - cannot
     * actually occur here).
     */
    public boolean cancel(UUID uuid) {
        return cancelOutcome(uuid) != AdminActions.CancelOutcome.NONE;
    }

    /** Handles a {@code usage}/{@code limit}/{@code cancel}/{@code pause}/{@code resume} admin action locally. */
    public void admin(String action, AdminActions.By by, AdminActions.Target target, List<String> args) {
        adminActions.handle(action, by, target, args);
    }

    // ---- worker (virtual thread) ----

    private AdminActions.CancelOutcome cancelOutcome(UUID uuid) {
        PlayerQueue pq = playerQueues.get(uuid);
        if (pq == null) {
            return AdminActions.CancelOutcome.NONE;
        }
        synchronized (pq) {
            AdminActions.CancelOutcome outcome;
            if (pq.running != null) {
                pq.running.cancelled().set(true);
                outcome = AdminActions.CancelOutcome.RUNNING;
            } else if (!pq.queue.isEmpty()) {
                outcome = AdminActions.CancelOutcome.QUEUED;
            } else {
                outcome = AdminActions.CancelOutcome.NONE;
            }
            for (Pending p : pq.queue) {
                p.cancelled().set(true);
            }
            pq.queue.clear();
            return outcome;
        }
    }

    private void runPending(UUID uuid, PlayerQueue pq, Pending pending) {
        MainThread.assertNotPrimary("AgentService.runPending");
        boolean acquired = false;
        try {
            concurrency.acquire();
            acquired = true;
            outbox.send(uuid, "Working on it...", false);
            processOne(uuid, pending);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) {
                concurrency.release();
            }
            advance(uuid, pq);
        }
    }

    private void advance(UUID uuid, PlayerQueue pq) {
        Pending next;
        synchronized (pq) {
            pq.running = null;
            next = pq.queue.pollFirst();
            if (next != null) {
                pq.running = next;
            }
        }
        if (next != null) {
            executor.execute(() -> runPending(uuid, pq, next));
        }
    }

    private void processOne(UUID uuid, Pending pending) {
        AgentRunner.PlayerInfo player = pending.player();
        double[] requestCost = {0};
        Usage[] requestUsage = {Usage.ZERO};
        UsageStore.RecordResult[] lastRecord = {null};

        ProgressThrottle progress = new ProgressThrottle(uuid);
        AgentRunner.RunResult result;
        try {
            result = runner.run(new AgentRunner.RunRequest(
                    player,
                    pending.text(),
                    historyStore.get(player.uuid()),
                    pending.cancelled()::get,
                    progress,
                    usage -> {
                        UsageStore.RecordResult r = usageStore.record(player.uuid(), player.name(), usage);
                        requestCost[0] += r.cost();
                        requestUsage[0] = requestUsage[0].plus(usage);
                        lastRecord[0] = r;
                    }));
        } catch (CancelledException e) {
            progress.cancelPending();
            outbox.send(uuid, "Cancelled.", true);
            return;
        } catch (RuntimeException e) {
            progress.cancelPending();
            logger.log(Level.SEVERE, e, () -> "[agent-service] request for " + player.name() + " failed");
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            outbox.send(uuid, "Something went wrong: " + truncate(message, ERROR_MESSAGE_MAX_LENGTH), true);
            return;
        }
        progress.cancelPending();

        if (pending.cancelled().get() || "Cancelled.".equals(result.text())) {
            outbox.send(uuid, "Cancelled.", true);
            return;
        }

        historyStore.append(player.uuid(), result.exchange());

        UsageStore.DayCounters today = lastRecord[0] != null
                ? lastRecord[0].today()
                : usageStore.summary(player.uuid(), player.name()).today();
        String footer = formatUsageFooter(requestCost[0], requestUsage[0], today, player.uuid());
        String finalText = result.text() + "\n" + footer;

        for (String chunk : chunkText(finalText, CHUNK_MAX_LENGTH)) {
            outbox.send(uuid, chunk, true);
        }
    }

    /** The {@code (this request: ... | today: ...)} footer, mirroring {@code service.ts}'s {@code formatUsageFooter}. */
    private String formatUsageFooter(double requestCost, Usage requestUsage, UsageStore.DayCounters today, String uuid) {
        long requestTokens = requestUsage.inputTokens() + requestUsage.cachedInputTokens() + requestUsage.outputTokens();
        long todayTokens = today.inputTokens + today.cachedInputTokens + today.outputTokens;
        PluginConfig.AgentConfig.PricingConfig pricing = config.pricing();
        boolean pricesAreZero = pricing.input() == 0 && pricing.cachedInput() == 0 && pricing.output() == 0;
        if (pricesAreZero) {
            return "(this request: " + UsageStore.fmtTokens(requestTokens) + " tokens | today: "
                    + UsageStore.fmtTokens(todayTokens) + " tokens)";
        }
        UsageStore.LimitValue costLimit = usageStore.effectiveLimit(uuid, UsageStore.LimitKind.COST);
        String ofPart = costLimit.isOff() ? "" : " of " + UsageStore.fmtCost(costLimit.amount(), pricing.currency());
        return "(this request: " + UsageStore.fmtTokens(requestTokens) + " tokens, "
                + UsageStore.fmtCost(requestCost, pricing.currency()) + " | today: "
                + UsageStore.fmtCost(today.cost, pricing.currency()) + ofPart + ")";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Port of {@code service.ts}'s {@code chunkText}: cuts on a line break, then a space, else at {@code max}. */
    static List<String> chunkText(String text, int max) {
        if (text.length() <= max) {
            return List.of(text);
        }
        List<String> chunks = new ArrayList<>();
        String rest = text;
        while (rest.length() > max) {
            int cut = rest.lastIndexOf('\n', max);
            if (cut <= 0) {
                cut = rest.lastIndexOf(' ', max);
            }
            if (cut <= 0) {
                cut = max;
            }
            chunks.add(rest.substring(0, cut));
            rest = rest.substring(cut).replaceFirst("^[\n ]+", "");
        }
        if (!rest.isEmpty()) {
            chunks.add(rest);
        }
        return chunks;
    }

    /** Coalesces progress lines to at most one per {@link #PROGRESS_THROTTLE_MS} per player, keeping the latest. */
    private final class ProgressThrottle implements Consumer<String> {
        private final UUID uuid;
        private final Object lock = new Object();
        private long lastSentAt = 0;
        private String pendingLine;
        private ScheduledFuture<?> timer;

        ProgressThrottle(UUID uuid) {
            this.uuid = uuid;
        }

        @Override
        public void accept(String line) {
            long now = System.currentTimeMillis();
            synchronized (lock) {
                if (now - lastSentAt >= PROGRESS_THROTTLE_MS) {
                    lastSentAt = now;
                    outbox.send(uuid, line, false);
                } else {
                    pendingLine = line;
                    if (timer == null) {
                        long delay = PROGRESS_THROTTLE_MS - (now - lastSentAt);
                        timer = progressScheduler.schedule(this::flush, delay, TimeUnit.MILLISECONDS);
                    }
                }
            }
        }

        private void flush() {
            String line;
            synchronized (lock) {
                timer = null;
                line = pendingLine;
                pendingLine = null;
                if (line == null) {
                    return;
                }
                lastSentAt = System.currentTimeMillis();
            }
            outbox.send(uuid, line, false);
        }

        /** Cancels any pending flush without firing it - called once the request is finished. */
        void cancelPending() {
            synchronized (lock) {
                if (timer != null) {
                    timer.cancel(false);
                    timer = null;
                }
            }
        }
    }

    // ---- shutdown ----

    /**
     * Stops accepting new work, flips every running/queued request's cancel flag (which also
     * cancels their tool {@link net.rcwalter.ashlar.rpc.InvocationContext}s, via {@link
     * AgentRunner}'s existing polling), then shuts the executor down and waits up to 5 seconds for
     * it to drain before flushing {@link UsageStore}. Idempotent.
     */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (PlayerQueue pq : playerQueues.values()) {
            synchronized (pq) {
                if (pq.running != null) {
                    pq.running.cancelled().set(true);
                }
                for (Pending p : pq.queue) {
                    p.cancelled().set(true);
                }
                pq.queue.clear();
            }
        }
        executor.shutdownNow();
        progressScheduler.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        usageStore.close();
    }
}
