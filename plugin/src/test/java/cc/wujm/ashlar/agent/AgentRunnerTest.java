// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

import com.google.gson.JsonObject;
import cc.wujm.ashlar.agent.model.ChatMessage;
import cc.wujm.ashlar.agent.model.ContentPart;
import cc.wujm.ashlar.agent.model.ToolCall;
import cc.wujm.ashlar.agent.model.Usage;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.ContentBlock;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolRegistry;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of {@code mcp-server/src/agent/runner.test.ts}. Unlike the TypeScript {@code runRequest},
 * {@link AgentRunner} does not own a {@code History}: it takes already-fetched history messages
 * in and returns the full exchange in {@link AgentRunner.RunResult#exchange()} for the caller to
 * persist (see the class javadoc) - so "records the exchange in history" and "a cancelled request
 * is not recorded" are ported as assertions on the returned exchange/result instead of on a
 * {@code History} object.
 */
class AgentRunnerTest {

    private static final Usage DEFAULT_USAGE = new Usage(10, 0, 5);

    private record ChatCall(List<ChatMessage> messages, List<cc.wujm.ashlar.agent.model.ToolDef> tools, ToolChoice toolChoice) {
    }

    private static final class FakeModelApi implements ModelApi {
        private final List<Reply> replies;
        private int index = 0;
        final List<ChatCall> calls = new ArrayList<>();

        FakeModelApi(List<Reply> replies) {
            this.replies = replies;
        }

        @Override
        public Reply chat(List<ChatMessage> messages, List<cc.wujm.ashlar.agent.model.ToolDef> tools, ToolChoice toolChoice,
                           java.util.function.BooleanSupplier cancelled) {
            calls.add(new ChatCall(messages, tools, toolChoice));
            if (index >= replies.size()) {
                throw new IllegalStateException("FakeModelApi: out of scripted replies");
            }
            return replies.get(index++);
        }
    }

    private static Reply assistantToolCallsReply(List<ToolCall> calls) {
        return assistantToolCallsReply(calls, DEFAULT_USAGE);
    }

    private static Reply assistantToolCallsReply(List<ToolCall> calls, Usage usage) {
        return new Reply(ChatMessage.assistantToolCalls(calls), usage, "tool_calls");
    }

    private static Reply assistantTextReply(String text) {
        return assistantTextReply(text, DEFAULT_USAGE);
    }

    private static Reply assistantTextReply(String text, Usage usage) {
        return new Reply(ChatMessage.assistantText(text), usage, "stop");
    }

    private static AgentRunner.PlayerInfo player() {
        return new AgentRunner.PlayerInfo("Steve", "p1", "world", new int[]{10, 70, -5}, "east", new int[]{11, 70, -5}, "SURVIVAL", "minecraft:stone_bricks at 12,71,-5 (west face)");
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

    private static AgentRunner.RunRequest request(String text, java.util.function.BooleanSupplier cancelled,
                                                    java.util.function.Consumer<String> onProgress) {
        return new AgentRunner.RunRequest(player(), text, List.of(), cancelled, onProgress, u -> { });
    }

    private static AgentRunner.RunRequest requestWithWrapUp(String text, java.util.function.BooleanSupplier wrapUp) {
        return new AgentRunner.RunRequest(player(), text, List.of(), () -> false, l -> { }, u -> { }, wrapUp);
    }

    @Test
    void twoToolCallTurnWhereOneToolReturnsImage() {
        FakeModelApi model = new FakeModelApi(List.of(
                assistantToolCallsReply(List.of(
                        new ToolCall("call-1", "mc_status", "{}"),
                        new ToolCall("call-2", "mc_render", "{\"view\":\"top\"}"))),
                assistantTextReply("Done.")));
        ToolRegistry registry = new ToolRegistry(List.of(
                fakeTool("mc_status", (ctx, args) -> CompletableFuture.completedFuture(ToolResult.text("ok"))),
                fakeTool("mc_render", (ctx, args) -> CompletableFuture.completedFuture(
                        ToolResult.content(List.of(ContentBlock.text("rendered"), ContentBlock.image("abc123", "image/png")))))));
        AgentRunner runner = new AgentRunner(model, registry, Set.of("mc_status", "mc_render"), 25, "high", Optional.empty());

        AgentRunner.RunResult result = runner.run(request("show me the area", () -> false, l -> { }));

        assertEquals("Done.", result.text());
        assertEquals(2, model.calls.size());

        List<ChatMessage> secondCallMessages = model.calls.get(1).messages();
        int assistantIndex = -1;
        for (int i = 0; i < secondCallMessages.size(); i++) {
            ChatMessage m = secondCallMessages.get(i);
            if (m.role() == ChatMessage.Role.ASSISTANT && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                assistantIndex = i;
                break;
            }
        }
        assertTrue(assistantIndex >= 0, "assistant tool_calls message must be present");

        ChatMessage toolMsg1 = secondCallMessages.get(assistantIndex + 1);
        ChatMessage toolMsg2 = secondCallMessages.get(assistantIndex + 2);
        assertEquals(ChatMessage.Role.TOOL, toolMsg1.role());
        assertEquals("call-1", toolMsg1.toolCallId());
        assertEquals("ok", toolMsg1.contentText());
        assertEquals(ChatMessage.Role.TOOL, toolMsg2.role());
        assertEquals("call-2", toolMsg2.toolCallId());
        assertEquals("rendered", toolMsg2.contentText());

        List<ChatMessage> rest = secondCallMessages.subList(assistantIndex + 3, secondCallMessages.size());
        List<ChatMessage> imageUserMessages = rest.stream()
                .filter(m -> m.role() == ChatMessage.Role.USER && m.contentParts() != null)
                .toList();
        assertEquals(1, imageUserMessages.size());
        List<ContentPart> parts = imageUserMessages.get(0).contentParts();
        assertEquals(1, parts.stream().filter(ContentPart::isImage).count());
        assertEquals(1, parts.stream().filter(ContentPart::isText).count());
    }

    @Test
    void toolBudgetExhaustionForcesToolChoiceNoneOnNextCall() {
        FakeModelApi model = new FakeModelApi(List.of(
                assistantToolCallsReply(List.of(new ToolCall("call-1", "mc_status", "{}"))),
                assistantTextReply("Summary after budget exhausted.")));
        ToolRegistry registry = new ToolRegistry(List.of(
                fakeTool("mc_status", (ctx, args) -> CompletableFuture.completedFuture(ToolResult.text("ok")))));
        AgentRunner runner = new AgentRunner(model, registry, Set.of("mc_status"), 1, "high", Optional.empty());

        AgentRunner.RunResult result = runner.run(request("do a lot of things", () -> false, l -> { }));

        assertEquals("Summary after budget exhausted.", result.text());
        assertEquals(2, model.calls.size());
        assertEquals(ToolChoice.AUTO, model.calls.get(0).toolChoice());
        assertEquals(ToolChoice.NONE, model.calls.get(1).toolChoice());
        ChatMessage lastMessage = model.calls.get(1).messages().get(model.calls.get(1).messages().size() - 1);
        assertTrue(lastMessage.contentText().contains("Tool budget exhausted"));
    }

    @Test
    void wrapUpFlippingTrueAfterFirstToolCallForcesToolChoiceNoneWithCreditNote() {
        FakeModelApi model = new FakeModelApi(List.of(
                assistantToolCallsReply(List.of(new ToolCall("call-1", "mc_status", "{}"))),
                assistantTextReply("Here is what I finished, snapshot abc123.")));
        ToolRegistry registry = new ToolRegistry(List.of(
                fakeTool("mc_status", (ctx, args) -> CompletableFuture.completedFuture(ToolResult.text("ok")))));
        AgentRunner runner = new AgentRunner(model, registry, Set.of("mc_status"), 25, "high", Optional.empty());

        // Simulates credit reaching zero once the first tool call's cost is deducted: wrapUp is
        // never true before the loop's second model call (see the next test), so returning true
        // unconditionally here still exercises "flips true after the first tool call".
        AgentRunner.RunResult result = runner.run(requestWithWrapUp("do a lot of things", () -> true));

        assertEquals("Here is what I finished, snapshot abc123.", result.text());
        assertEquals(2, model.calls.size());
        assertEquals(ToolChoice.AUTO, model.calls.get(0).toolChoice());
        assertEquals(ToolChoice.NONE, model.calls.get(1).toolChoice());
        ChatMessage lastMessage = model.calls.get(1).messages().get(model.calls.get(1).messages().size() - 1);
        assertTrue(lastMessage.contentText().contains("credit is used up"), "unexpected note: " + lastMessage.contentText());
        assertEquals(1, result.toolCalls(), "no further tool calls once wrapped up");
    }

    @Test
    void wrapUpIsNotCheckedBeforeTheFirstModelCall() {
        FakeModelApi model = new FakeModelApi(List.of(assistantTextReply("Done immediately.")));
        AgentRunner runner = new AgentRunner(model, new ToolRegistry(List.of()), Set.of(), 25, "high", Optional.empty());

        AgentRunner.RunResult result = runner.run(requestWithWrapUp("hi", () -> {
            throw new AssertionError("wrapUp must not be checked before the first model call");
        }));

        assertEquals("Done immediately.", result.text());
        assertEquals(ToolChoice.AUTO, model.calls.get(0).toolChoice());
    }

    @Test
    void cancellationAfterFirstToolCallStopsBeforeSecond() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean mcRenderCalled = new AtomicBoolean(false);
        FakeModelApi model = new FakeModelApi(List.of(
                assistantToolCallsReply(List.of(
                        new ToolCall("call-1", "mc_status", "{}"),
                        new ToolCall("call-2", "mc_render", "{}")))));
        ToolRegistry registry = new ToolRegistry(List.of(
                fakeTool("mc_status", (ctx, args) -> {
                    cancelled.set(true);
                    return CompletableFuture.completedFuture(ToolResult.text("ok"));
                }),
                fakeTool("mc_render", (ctx, args) -> {
                    mcRenderCalled.set(true);
                    return CompletableFuture.completedFuture(ToolResult.text("should not run"));
                })));
        AgentRunner runner = new AgentRunner(model, registry, Set.of("mc_status", "mc_render"), 25, "high", Optional.empty());

        AgentRunner.RunResult result = runner.run(request("build something", cancelled::get, l -> { }));

        assertEquals("Cancelled.", result.text());
        assertFalse(mcRenderCalled.get());
        assertEquals(1, model.calls.size());
    }

    @Test
    void unknownToolNameHandledAsErrorResultNotThrow() {
        FakeModelApi model = new FakeModelApi(List.of(
                assistantToolCallsReply(List.of(new ToolCall("call-1", "totally_unknown_tool", "{}"))),
                assistantTextReply("I could not find that tool.")));
        ToolRegistry registry = new ToolRegistry(List.of(
                fakeTool("mc_status", (ctx, args) -> CompletableFuture.completedFuture(ToolResult.text("ok")))));
        AgentRunner runner = new AgentRunner(model, registry, Set.of("mc_status"), 25, "high", Optional.empty());

        AgentRunner.RunResult result = runner.run(request("call something weird", () -> false, l -> { }));

        assertEquals("I could not find that tool.", result.text());
        assertEquals(2, model.calls.size());
        ChatMessage toolMessage = model.calls.get(1).messages().stream()
                .filter(m -> m.role() == ChatMessage.Role.TOOL && "call-1".equals(m.toolCallId()))
                .findFirst().orElseThrow();
        assertTrue(toolMessage.contentText().contains("Unknown tool \"totally_unknown_tool\""));
    }

    @Test
    void progressCallbackReportsToolNameAndShortArgs() {
        FakeModelApi model = new FakeModelApi(List.of(
                assistantToolCallsReply(List.of(new ToolCall("call-1", "mc_build",
                        "{\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"minecraft:stone\"}"))),
                assistantTextReply("Built it.")));
        ToolRegistry registry = new ToolRegistry(List.of(
                fakeTool("mc_build", (ctx, args) -> CompletableFuture.completedFuture(ToolResult.text("done")))));
        AgentRunner runner = new AgentRunner(model, registry, Set.of("mc_build"), 25, "high", Optional.empty());

        List<String> progressLines = new ArrayList<>();
        AgentRunner.RunResult result = runner.run(request("build a cube", () -> false, progressLines::add));

        assertEquals("Built it.", result.text());
        assertEquals(1, progressLines.size());
        assertEquals("> mc_build from=[0,0,0] to=[1,1,1]", progressLines.get(0));
    }

    @Test
    void noToolCallsReturnsTrimmedAssistantTextDirectly() {
        FakeModelApi model = new FakeModelApi(List.of(assistantTextReply("  Hello there.  ")));
        AgentRunner runner = new AgentRunner(model, new ToolRegistry(List.of()), Set.of(), 25, "high", Optional.empty());

        AgentRunner.RunResult result = runner.run(request("hi", () -> false, l -> { }));

        assertEquals("Hello there.", result.text());
    }

    @Test
    void emptyAssistantReplyBecomesNoReply() {
        FakeModelApi model = new FakeModelApi(List.of(assistantTextReply("")));
        AgentRunner runner = new AgentRunner(model, new ToolRegistry(List.of()), Set.of(), 25, "high", Optional.empty());

        AgentRunner.RunResult result = runner.run(request("hi", () -> false, l -> { }));

        assertEquals("(no reply)", result.text());
    }

    @Test
    void returnsTheFullExchangeForTheCallerToRecord() {
        FakeModelApi model = new FakeModelApi(List.of(assistantTextReply("First reply.")));
        AgentRunner runner = new AgentRunner(model, new ToolRegistry(List.of()), Set.of(), 25, "high", Optional.empty());

        AgentRunner.RunResult result = runner.run(request("first message", () -> false, l -> { }));

        List<ChatMessage> exchange = result.exchange();
        assertEquals(2, exchange.size());
        assertEquals(ChatMessage.Role.USER, exchange.get(0).role());
        assertEquals("first message", exchange.get(0).contentText());
        assertEquals(ChatMessage.Role.ASSISTANT, exchange.get(1).role());
        assertEquals("First reply.", exchange.get(1).contentText());
    }

    @Test
    void aCancelledRequestMakesNoModelCallsAndReturnsCancelled() {
        FakeModelApi model = new FakeModelApi(List.of());
        AgentRunner runner = new AgentRunner(model, new ToolRegistry(List.of()), Set.of(), 25, "high", Optional.empty());

        AgentRunner.RunResult result = runner.run(request("too late", () -> true, l -> { }));

        assertEquals("Cancelled.", result.text());
        assertEquals(0, model.calls.size());
    }

    @Test
    void sumsUsageAndCountsToolCallsAcrossEveryModelCall() {
        FakeModelApi model = new FakeModelApi(List.of(
                assistantToolCallsReply(List.of(new ToolCall("call-1", "mc_status", "{}")), new Usage(100, 20, 10)),
                assistantToolCallsReply(List.of(
                        new ToolCall("call-2", "mc_status", "{}"),
                        new ToolCall("call-3", "mc_status", "{}")), new Usage(200, 0, 15)),
                assistantTextReply("Done.", new Usage(50, 5, 8))));
        ToolRegistry registry = new ToolRegistry(List.of(
                fakeTool("mc_status", (ctx, args) -> CompletableFuture.completedFuture(ToolResult.text("ok")))));
        AgentRunner runner = new AgentRunner(model, registry, Set.of("mc_status"), 25, "high", Optional.empty());

        AgentRunner.RunResult result = runner.run(request("check status a few times", () -> false, l -> { }));

        assertEquals("Done.", result.text());
        assertEquals(3, result.toolCalls());
        assertEquals(new Usage(350, 25, 33), result.usage());
    }
}
