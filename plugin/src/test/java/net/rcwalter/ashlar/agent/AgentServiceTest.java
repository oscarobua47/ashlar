// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import com.google.gson.JsonObject;
import net.rcwalter.ashlar.agent.model.ChatMessage;
import net.rcwalter.ashlar.agent.model.ToolCall;
import net.rcwalter.ashlar.agent.model.ToolDef;
import net.rcwalter.ashlar.agent.model.Usage;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.tool.ContentBlock;
import net.rcwalter.ashlar.tool.Tool;
import net.rcwalter.ashlar.tool.ToolRegistry;
import net.rcwalter.ashlar.tool.ToolResult;
import net.rcwalter.ashlar.tool.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link AgentService} (docs/private/prompts/step8b-prompt.md &sect;5): per-player
 * queueing, the global concurrency semaphore, paused/limit rejection wording, the usage footer,
 * progress throttling, cancelling a running and a queued request, and shutdown.
 */
class AgentServiceTest {

    private static final Logger LOGGER = Logger.getLogger("AgentServiceTest");
    private static final UUID PLAYER_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path tempDir;

    private AgentService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    // ---- fakes ----

    /** Records every {@link Outbox#send} call in order; thread-safe for the virtual-thread worker. */
    private static final class RecordingOutbox implements Outbox {
        record Sent(UUID uuid, String text, boolean finalKind) {
        }

        final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(UUID uuid, String text, boolean finalKind) {
            sent.add(new Sent(uuid, text, finalKind));
        }

        List<Sent> forPlayer(UUID uuid) {
            return sent.stream().filter(s -> s.uuid().equals(uuid)).toList();
        }
    }

    /** Blocks only its first {@code chat()} call until {@link #release()} is called, polling {@code cancelled} meanwhile; later calls return their scripted reply immediately. */
    private static final class BlockingModelApi implements ModelApi {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final java.util.Queue<Reply> replies;
        private final AtomicInteger calls = new AtomicInteger();

        BlockingModelApi(Reply... replies) {
            this.replies = new java.util.concurrent.ConcurrentLinkedQueue<>(List.of(replies));
        }

        @Override
        public Reply chat(List<ChatMessage> messages, List<ToolDef> tools, ToolChoice toolChoice, BooleanSupplier cancelled) {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    while (!release.await(20, TimeUnit.MILLISECONDS)) {
                        if (cancelled.getAsBoolean()) {
                            throw new CancelledException();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                }
            }
            Reply r = replies.poll();
            if (r == null) {
                throw new IllegalStateException("BlockingModelApi: out of scripted replies");
            }
            return r;
        }

        void awaitEntered() {
            await(entered);
        }

        void release() {
            release.countDown();
        }

        int callCount() {
            return calls.get();
        }
    }

    /** Returns scripted replies in order, one per call; never blocks. */
    private static final class ScriptedModelApi implements ModelApi {
        private final List<Reply> replies;
        private int index = 0;

        ScriptedModelApi(List<Reply> replies) {
            this.replies = replies;
        }

        @Override
        public synchronized Reply chat(List<ChatMessage> messages, List<ToolDef> tools, ToolChoice toolChoice, BooleanSupplier cancelled) {
            if (index >= replies.size()) {
                throw new IllegalStateException("ScriptedModelApi: out of scripted replies");
            }
            return replies.get(index++);
        }
    }

    private static Reply textReply(String text, Usage usage) {
        return new Reply(ChatMessage.assistantText(text), usage, "stop");
    }

    private static Reply toolCallsReply(List<ToolCall> calls) {
        return new Reply(ChatMessage.assistantToolCalls(calls), Usage.ZERO, "tool_calls");
    }

    private static Tool fakeTool(String name, BiFunction<InvocationContext, JsonObject, CompletableFuture<ToolResult>> impl) {
        ToolSpec spec = new ToolSpec(name, name, "", new JsonObject(), new JsonObject());
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
                return impl.apply(ctx, args);
            }
        };
    }

    private static AgentRunner.PlayerInfo player(UUID uuid, String name) {
        return new AgentRunner.PlayerInfo(name, uuid.toString(), "world", new int[]{0, 70, 0}, "south", new int[]{0, 70, 1}, "SURVIVAL", null);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for latch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting");
        }
    }

    private static void awaitTrue(Supplier<Boolean> condition, String what) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what);
            }
        }
        fail("timed out waiting for " + what);
    }

    private PluginConfig.AgentConfig agentConfig(int maxConcurrent, int maxRequestsPerDay, double maxCostPerDay) {
        return new PluginConfig.AgentConfig(
                PluginConfig.AgentConfig.Mode.EMBEDDED, 5, 500, true, false,
                new PluginConfig.AgentConfig.ModelConfig("http://localhost", "test-key", "test-model", 25, 120_000,
                        "high", "", false),
                new PluginConfig.AgentConfig.LimitsConfig(maxRequestsPerDay, 0, maxCostPerDay, maxConcurrent, 6, 30),
                new PluginConfig.AgentConfig.PricingConfig(0.30, 0.006, 1.20, "USD", "always", 0.5));
    }

    private AgentService newService(PluginConfig.AgentConfig config, ModelApi modelApi, RecordingOutbox outbox) {
        UsageStore usageStore = new UsageStore(tempDir.resolve("usage.json"), config.pricing().input(),
                config.pricing().cachedInput(), config.pricing().output(), config.pricing().currency(),
                new UsageStore.Limits(config.limits().maxCostPerPlayerPerDay(), config.limits().maxTokensPerPlayerPerDay(),
                        config.limits().maxRequestsPerPlayerPerDay()),
                Pricing.parsePeakHours(config.pricing().peakHours()), config.pricing().offPeakMultiplier(),
                java.time.Instant::now, 0);
        HistoryStore historyStore = new HistoryStore(config.limits().historyTurns(), config.limits().historyTtlMinutes());
        ToolRegistry emptyRegistry = new ToolRegistry(List.of());
        service = new AgentService(config, emptyRegistry, modelApi, usageStore, historyStore, outbox, LOGGER);
        return service;
    }

    @Test
    void queueingBehindARunningRequestSendsQueuedThenRunsInOrder() {
        BlockingModelApi first = new BlockingModelApi(
                textReply("First done.", new Usage(10, 0, 5)), textReply("Second done.", new Usage(10, 0, 5)));
        RecordingOutbox outbox = new RecordingOutbox();
        AgentService svc = newService(agentConfig(2, 0, 0), first, outbox);

        svc.submit(player(PLAYER_1, "Alex"), "first request");
        first.awaitEntered();
        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(s -> s.text().equals("Working on it...")),
                "first request's Working on it...");

        svc.submit(player(PLAYER_1, "Alex"), "second request");
        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(s -> s.text().equals("Queued behind your previous request.")),
                "queued message");

        first.release();
        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(s -> s.finalKind() && s.text().startsWith("First done.")),
                "first request's final reply");
        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(s -> s.finalKind() && s.text().startsWith("Second done.")),
                "second (queued) request's final reply");
        assertEquals(2, first.callCount());

        List<RecordingOutbox.Sent> finals = outbox.forPlayer(PLAYER_1).stream().filter(RecordingOutbox.Sent::finalKind).toList();
        assertTrue(finals.get(0).text().startsWith("First done."), "requests must finish in FIFO order");
        assertTrue(finals.get(1).text().startsWith("Second done."), "requests must finish in FIFO order");
    }

    @Test
    void globalConcurrencyLimitDelaysWorkingOnItForASecondPlayer() {
        BlockingModelApi first = new BlockingModelApi(textReply("Done 1.", new Usage(1, 0, 1)));
        RecordingOutbox outbox = new RecordingOutbox();
        AgentService svc = newService(agentConfig(1, 0, 0), first, outbox);

        svc.submit(player(PLAYER_1, "Alex"), "request one");
        first.awaitEntered();
        svc.submit(player(PLAYER_2, "Steve"), "request two");

        // Player 2 cannot acquire the single global slot while player 1 holds it.
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(outbox.forPlayer(PLAYER_2).isEmpty(), "player 2 must not start while the only slot is held");

        first.release();
        awaitTrue(() -> outbox.forPlayer(PLAYER_2).stream().anyMatch(s -> s.text().equals("Working on it...")),
                "player 2's Working on it... once the slot frees up");
    }

    @Test
    void pausedRejectsImmediatelyWithFinalMessageAndNoModelCall() {
        ScriptedModelApi model = new ScriptedModelApi(List.of());
        RecordingOutbox outbox = new RecordingOutbox();
        AgentService svc = newService(agentConfig(2, 0, 0), model, outbox);

        // Reach into the store the same way an admin "pause" action would.
        svc.admin("pause", new AdminActions.By("Op", "00000000-0000-0000-0000-0000000000aa"), null, List.of());

        svc.submit(player(PLAYER_1, "Alex"), "build something");

        awaitTrue(() -> !outbox.forPlayer(PLAYER_1).isEmpty(), "a rejection message");
        RecordingOutbox.Sent sent = outbox.forPlayer(PLAYER_1).get(0);
        assertTrue(sent.finalKind());
        assertTrue(sent.text().contains("paused"));
    }

    @Test
    void overDailyRequestLimitRejectsWithFinalMessage() {
        ScriptedModelApi model = new ScriptedModelApi(List.of());
        RecordingOutbox outbox = new RecordingOutbox();
        // max-requests-per-player-per-day = 1, already used up by the record() call below.
        PluginConfig.AgentConfig config = agentConfig(2, 1, 0);
        UsageStore usageStore = new UsageStore(tempDir.resolve("usage.json"), config.pricing().input(),
                config.pricing().cachedInput(), config.pricing().output(), config.pricing().currency(),
                new UsageStore.Limits(0, 0, 1), Pricing.parsePeakHours("always"), 0.5, java.time.Instant::now, 0);
        usageStore.record(PLAYER_1.toString(), "Alex", new Usage(1, 0, 1));
        HistoryStore historyStore = new HistoryStore(6, 30);
        service = new AgentService(config, new ToolRegistry(List.of()), model, usageStore, historyStore, outbox, LOGGER);

        service.submit(player(PLAYER_1, "Alex"), "second - should be rejected");
        awaitTrue(() -> !outbox.forPlayer(PLAYER_1).isEmpty(), "a limit rejection message");
        RecordingOutbox.Sent sent = outbox.forPlayer(PLAYER_1).get(0);
        assertTrue(sent.finalKind());
        assertTrue(sent.text().contains("daily request limit"), "unexpected message: " + sent.text());
    }

    @Test
    void finalReplyCarriesTheUsageFooter() {
        ScriptedModelApi model = new ScriptedModelApi(List.of(textReply("Built it.", new Usage(1_000_000, 0, 0))));
        RecordingOutbox outbox = new RecordingOutbox();
        AgentService svc = newService(agentConfig(2, 0, 0), model, outbox);

        svc.submit(player(PLAYER_1, "Alex"), "build a house");

        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(RecordingOutbox.Sent::finalKind), "the final reply");
        RecordingOutbox.Sent finalSent = outbox.forPlayer(PLAYER_1).stream().filter(RecordingOutbox.Sent::finalKind).findFirst().orElseThrow();
        assertTrue(finalSent.text().startsWith("Built it."));
        assertTrue(finalSent.text().contains("(this request: 1M tokens, $0.30 | today: $0.30)"),
                "unexpected footer: " + finalSent.text());
    }

    @Test
    void historyKeepsOnlyThePlayersTextAndTheFinalReply() {
        PluginConfig.AgentConfig config = agentConfig(2, 0, 0);
        ScriptedModelApi model = new ScriptedModelApi(List.of(
                toolCallsReply(List.of(new ToolCall("call-1", "nope", "{}"))),
                textReply("Built it at 1,2,3.", new Usage(10, 0, 5))));
        RecordingOutbox outbox = new RecordingOutbox();
        UsageStore usageStore = new UsageStore(tempDir.resolve("usage.json"), 0, 0, 0, "USD",
                new UsageStore.Limits(0, 0, 0), Pricing.parsePeakHours("always"), 0.5, java.time.Instant::now, 0);
        HistoryStore historyStore = new HistoryStore(6, 30);
        service = new AgentService(config, new ToolRegistry(List.of()), model, usageStore, historyStore, outbox, LOGGER);

        service.submit(player(PLAYER_1, "Alex"), "build a house");
        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(RecordingOutbox.Sent::finalKind), "the final reply");

        List<ChatMessage> remembered = historyStore.get(PLAYER_1.toString());
        assertEquals(2, remembered.size(), "tool traffic must not be remembered: " + remembered);
        assertEquals(ChatMessage.Role.USER, remembered.get(0).role());
        assertEquals("build a house", remembered.get(0).contentText());
        assertEquals(ChatMessage.Role.ASSISTANT, remembered.get(1).role());
        assertEquals("Built it at 1,2,3.", remembered.get(1).contentText());
        assertTrue(service.reset(PLAYER_1));
        assertTrue(historyStore.get(PLAYER_1.toString()).isEmpty());
    }

    @Test
    void progressLinesAreThrottledToOnePer1500MsCoalescingToTheLatest() {
        ScriptedModelApi model = new ScriptedModelApi(List.of(
                toolCallsReply(List.of(new ToolCall("call-1", "tool_a", "{}"), new ToolCall("call-2", "tool_b", "{}"))),
                textReply("Done.", Usage.ZERO)));
        RecordingOutbox outbox = new RecordingOutbox();
        PluginConfig.AgentConfig config = agentConfig(2, 0, 0);
        UsageStore usageStore = new UsageStore(tempDir.resolve("usage.json"), config.pricing().input(),
                config.pricing().cachedInput(), config.pricing().output(), config.pricing().currency(),
                new UsageStore.Limits(0, 0, 0), Pricing.parsePeakHours("always"), 0.5, java.time.Instant::now, 0);
        HistoryStore historyStore = new HistoryStore(6, 30);
        // tool_b's own execution takes 2s - longer than the 1.5s throttle window - so the request
        // is still in flight (and AgentService has not yet cancelled the pending flush timer,
        // which happens once the whole request finishes) when the coalesced line is due to fire.
        ToolRegistry registry = new ToolRegistry(List.of(
                fakeTool("tool_a", (ctx, args) -> CompletableFuture.completedFuture(ToolResult.text("a-done"))),
                fakeTool("tool_b", (ctx, args) -> CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolResult.text("b-done");
                }))));
        service = new AgentService(config, registry, model, usageStore, historyStore, outbox, LOGGER);

        service.submit(player(PLAYER_1, "Alex"), "do two things");

        // The two tool calls fire onProgress back-to-back (well under 1.5s apart): only the first
        // is sent immediately, the second is coalesced and flushed roughly 1.5s later.
        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(s -> s.text().equals("> tool_a")), "first progress line");
        assertFalse(outbox.forPlayer(PLAYER_1).stream().anyMatch(s -> s.text().equals("> tool_b")),
                "second progress line must not appear immediately");

        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(s -> s.text().equals("> tool_b")),
                "second (coalesced) progress line after the throttle window");
    }

    @Test
    void cancelOfARunningRequestOverridesANormalReplyWithCancelled() {
        BlockingModelApi model = new BlockingModelApi(textReply("Would have finished.", new Usage(5, 0, 5)));
        RecordingOutbox outbox = new RecordingOutbox();
        AgentService svc = newService(agentConfig(2, 0, 0), model, outbox);

        svc.submit(player(PLAYER_1, "Alex"), "build a big tower");
        model.awaitEntered();

        assertTrue(svc.cancel(PLAYER_1));

        model.release();
        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(s -> s.finalKind() && s.text().equals("Cancelled.")),
                "the Cancelled. final reply");
    }

    @Test
    void cancelDropsAQueuedRequestSoItNeverRuns() {
        BlockingModelApi first = new BlockingModelApi(textReply("First.", new Usage(1, 0, 1)));
        RecordingOutbox outbox = new RecordingOutbox();
        AgentService svc = newService(agentConfig(2, 0, 0), first, outbox);

        svc.submit(player(PLAYER_1, "Alex"), "first request");
        first.awaitEntered();
        svc.submit(player(PLAYER_1, "Alex"), "second request - will be queued then dropped");
        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(s -> s.text().equals("Queued behind your previous request.")),
                "queued message for the second request");

        assertTrue(svc.cancel(PLAYER_1));
        first.release();

        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(s -> s.finalKind() && s.text().equals("Cancelled.")),
                "the running request's Cancelled. reply");

        // Give the (now-empty) queue a chance to be drained if it were going to run - it must not.
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long workingOnItCount = outbox.forPlayer(PLAYER_1).stream().filter(s -> s.text().equals("Working on it...")).count();
        assertEquals(1, workingOnItCount, "the dropped, queued request must never start");
    }

    @Test
    void footerGainsCreditLineWhenEnabled() {
        ScriptedModelApi model = new ScriptedModelApi(List.of(textReply("Built it.", new Usage(1_000_000, 0, 0))));
        RecordingOutbox outbox = new RecordingOutbox();
        PluginConfig.AgentConfig config = agentConfig(2, 0, 0);
        UsageStore usageStore = new UsageStore(tempDir.resolve("usage.json"), config.pricing().input(),
                config.pricing().cachedInput(), config.pricing().output(), config.pricing().currency(),
                new UsageStore.Limits(0, 0, 0), Pricing.parsePeakHours("always"), 0.5, java.time.Instant::now, 0);
        usageStore.addCredit(PLAYER_1.toString(), "Alex", 3.5);
        HistoryStore historyStore = new HistoryStore(6, 30);
        service = new AgentService(config, new ToolRegistry(List.of()), model, usageStore, historyStore, outbox, LOGGER);

        service.submit(player(PLAYER_1, "Alex"), "build a house");

        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(RecordingOutbox.Sent::finalKind), "the final reply");
        RecordingOutbox.Sent finalSent = outbox.forPlayer(PLAYER_1).stream().filter(RecordingOutbox.Sent::finalKind).findFirst().orElseThrow();
        assertTrue(finalSent.text().contains("| credit: $3.20 left)"), "unexpected footer: " + finalSent.text());
    }

    @Test
    void submitRejectsImmediatelyWhenCreditIsAlreadyZero() {
        ScriptedModelApi model = new ScriptedModelApi(List.of());
        RecordingOutbox outbox = new RecordingOutbox();
        PluginConfig.AgentConfig config = agentConfig(2, 0, 0);
        UsageStore usageStore = new UsageStore(tempDir.resolve("usage.json"), config.pricing().input(),
                config.pricing().cachedInput(), config.pricing().output(), config.pricing().currency(),
                new UsageStore.Limits(0, 0, 0), Pricing.parsePeakHours("always"), 0.5, java.time.Instant::now, 0);
        usageStore.setCredit(PLAYER_1.toString(), "Alex", 0);
        HistoryStore historyStore = new HistoryStore(6, 30);
        service = new AgentService(config, new ToolRegistry(List.of()), model, usageStore, historyStore, outbox, LOGGER);

        service.submit(player(PLAYER_1, "Alex"), "build a house");

        awaitTrue(() -> !outbox.forPlayer(PLAYER_1).isEmpty(), "a rejection message");
        RecordingOutbox.Sent sent = outbox.forPlayer(PLAYER_1).get(0);
        assertTrue(sent.finalKind());
        assertTrue(sent.text().contains("out of credit"), "unexpected message: " + sent.text());
    }

    @Test
    void wrapUpPathAppendsTheExtraCreditUsedUpLine() {
        // The first turn's $0.30 (1M input tokens at the configured $0.30/1M, "always"-peak price)
        // overdraws the $0.10 starting balance, so the wrapUp supplier - read fresh off the store,
        // which record() already updated - is true before the runner's second chat() call.
        ScriptedModelApi model = new ScriptedModelApi(List.of(
                new Reply(ChatMessage.assistantToolCalls(List.of(new ToolCall("call-1", "tool_a", "{}"))),
                        new Usage(1_000_000, 0, 0), "tool_calls"),
                textReply("Wall built, roof still to do. Snapshot xyz.", new Usage(10, 0, 5))));
        RecordingOutbox outbox = new RecordingOutbox();
        PluginConfig.AgentConfig config = agentConfig(2, 0, 0);
        UsageStore usageStore = new UsageStore(tempDir.resolve("usage.json"), config.pricing().input(),
                config.pricing().cachedInput(), config.pricing().output(), config.pricing().currency(),
                new UsageStore.Limits(0, 0, 0), Pricing.parsePeakHours("always"), 0.5, java.time.Instant::now, 0);
        usageStore.setCredit(PLAYER_1.toString(), "Alex", 0.10);
        HistoryStore historyStore = new HistoryStore(6, 30);
        ToolRegistry registry = new ToolRegistry(List.of(
                fakeTool("tool_a", (ctx, args) -> CompletableFuture.completedFuture(ToolResult.text("a-done")))));
        service = new AgentService(config, registry, model, usageStore, historyStore, outbox, LOGGER);

        service.submit(player(PLAYER_1, "Alex"), "build a house");

        awaitTrue(() -> outbox.forPlayer(PLAYER_1).stream().anyMatch(RecordingOutbox.Sent::finalKind), "the final reply");
        RecordingOutbox.Sent finalSent = outbox.forPlayer(PLAYER_1).stream().filter(RecordingOutbox.Sent::finalKind).findFirst().orElseThrow();
        assertTrue(finalSent.text().contains("Credit used up - ask an operator to top up, then say \"continue\"."),
                "unexpected final message: " + finalSent.text());
    }

    @Test
    void shutdownCancelsARunningRequestAndReturnsPromptly() {
        BlockingModelApi model = new BlockingModelApi(textReply("Never sent.", new Usage(1, 0, 1)));
        RecordingOutbox outbox = new RecordingOutbox();
        AgentService svc = newService(agentConfig(2, 0, 0), model, outbox);

        svc.submit(player(PLAYER_1, "Alex"), "long request");
        model.awaitEntered();

        long startedAt = System.currentTimeMillis();
        svc.shutdown();
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertTrue(elapsedMs < 5000, "shutdown must complete within its 5s budget, took " + elapsedMs + "ms");
        service = null; // already shut down; tearDown must not shut it down again
    }
}
