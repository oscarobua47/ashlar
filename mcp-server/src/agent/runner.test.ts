// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { test } from "node:test";

import type { AgentConfig } from "./config.js";
import { HistoryStore } from "./history.js";
import type { ChatCompletionOptions, ChatCompletionResult, ToolDef } from "./provider.js";
import { runRequest, type PlayerInfo } from "./runner.js";
import type { ToolBridge, ToolCallResult } from "./tools.js";

function baseConfig(overrides: Partial<AgentConfig> = {}): AgentConfig {
    return {
        baseUrl: "http://127.0.0.1:1",
        apiKey: "test-key",
        model: "test-model",
        maxToolCalls: 25,
        maxRequestsPerPlayerPerDay: 40,
        allowCommand: false,
        maxConcurrent: 2,
        historyTurns: 6,
        historyTtlMinutes: 30,
        imageDetail: "high",
        requestTimeoutMs: 5000,
        ...overrides
    };
}

function player(): PlayerInfo {
    return {
        name: "Steve",
        uuid: "p1",
        world: "world",
        pos: [10, 70, -5],
        facing: "east",
        inFront: [11, 70, -5],
        gameMode: "SURVIVAL"
    };
}

/** A scripted chatFn: returns each entry of `replies` in order, recording the options it was called with. */
function scriptedProvider(replies: ChatCompletionResult[]) {
    let i = 0;
    const calls: ChatCompletionOptions[] = [];
    const fn = async (_cfg: AgentConfig, opts: ChatCompletionOptions): Promise<ChatCompletionResult> => {
        calls.push(opts);
        if (i >= replies.length) throw new Error("scriptedProvider: out of scripted replies");
        return replies[i++]!;
    };
    return { fn, calls };
}

function assistantToolCalls(calls: Array<{ id: string; name: string; args: string }>): ChatCompletionResult {
    return {
        message: {
            role: "assistant",
            content: null,
            tool_calls: calls.map(c => ({ id: c.id, type: "function", function: { name: c.name, arguments: c.args } }))
        },
        usage: { promptTokens: 10, completionTokens: 5 },
        finishReason: "tool_calls"
    };
}

function assistantText(text: string): ChatCompletionResult {
    return {
        message: { role: "assistant", content: text },
        usage: { promptTokens: 10, completionTokens: 5 },
        finishReason: "stop"
    };
}

function fakeBridge(impls: Record<string, (args: unknown) => Promise<ToolCallResult>>): ToolBridge {
    const tools: ToolDef[] = Object.keys(impls).map(name => ({
        type: "function",
        function: { name, description: "", parameters: {} }
    }));
    return {
        tools,
        callTool: async (name, args) => {
            const impl = impls[name];
            if (!impl) {
                return { text: `Unknown tool "${name}". Valid tools: ${Object.keys(impls).join(", ")}.`, images: [], isError: true };
            }
            return impl(args);
        },
        close: () => {}
    };
}

function newHistory(): HistoryStore {
    return new HistoryStore({ turns: 6, ttlMinutes: 30 });
}

test("runRequest: a two-tool-call turn where one tool returns an image - tool messages then exactly one user message with the image part", async () => {
    const { fn, calls } = scriptedProvider([
        assistantToolCalls([
            { id: "call-1", name: "mc_status", args: "{}" },
            { id: "call-2", name: "mc_render", args: '{"view":"top"}' }
        ]),
        assistantText("Done.")
    ]);
    const bridge = fakeBridge({
        mc_status: async () => ({ text: "ok", images: [], isError: false }),
        mc_render: async () => ({ text: "rendered", images: [{ data: "abc123", mimeType: "image/png" }], isError: false })
    });

    const result = await runRequest({
        cfg: baseConfig(),
        bridge,
        history: newHistory(),
        player: player(),
        text: "show me the area",
        signal: new AbortController().signal,
        onProgress: () => {},
        chatFn: fn
    });

    assert.equal(result, "Done.");
    assert.equal(calls.length, 2);

    const secondCallMessages = calls[1]!.messages;
    const assistantIndex = secondCallMessages.findIndex(m => m.role === "assistant" && (m.tool_calls?.length ?? 0) > 0);
    assert.ok(assistantIndex >= 0, "assistant tool_calls message must be present");

    const toolMsg1 = secondCallMessages[assistantIndex + 1]!;
    const toolMsg2 = secondCallMessages[assistantIndex + 2]!;
    assert.equal(toolMsg1.role, "tool");
    assert.equal(toolMsg1.tool_call_id, "call-1");
    assert.equal(toolMsg1.content, "ok");
    assert.equal(toolMsg2.role, "tool");
    assert.equal(toolMsg2.tool_call_id, "call-2");
    assert.equal(toolMsg2.content, "rendered");

    // Exactly one user message follows, carrying the one tool call's image.
    const rest = secondCallMessages.slice(assistantIndex + 3);
    const imageUserMessages = rest.filter(m => m.role === "user" && Array.isArray(m.content));
    assert.equal(imageUserMessages.length, 1);
    const parts = imageUserMessages[0]!.content as Array<{ type: string }>;
    assert.equal(parts.filter(p => p.type === "image_url").length, 1);
    assert.equal(parts.filter(p => p.type === "text").length, 1);
});

test("runRequest: tool budget exhaustion forces tool_choice:none on the next call", async () => {
    const { fn, calls } = scriptedProvider([
        assistantToolCalls([{ id: "call-1", name: "mc_status", args: "{}" }]),
        assistantText("Summary after budget exhausted.")
    ]);
    const bridge = fakeBridge({
        mc_status: async () => ({ text: "ok", images: [], isError: false })
    });

    const result = await runRequest({
        cfg: baseConfig({ maxToolCalls: 1 }),
        bridge,
        history: newHistory(),
        player: player(),
        text: "do a lot of things",
        signal: new AbortController().signal,
        onProgress: () => {},
        chatFn: fn
    });

    assert.equal(result, "Summary after budget exhausted.");
    assert.equal(calls.length, 2);
    assert.equal(calls[0]!.toolChoice, undefined);
    assert.equal(calls[1]!.toolChoice, "none");
    const lastMessage = calls[1]!.messages[calls[1]!.messages.length - 1]!;
    assert.match(String(lastMessage.content), /Tool budget exhausted/);
});

test("runRequest: cancellation after the first tool call stops before the second", async () => {
    const controller = new AbortController();
    let mcRenderCalled = false;
    const { fn, calls } = scriptedProvider([
        assistantToolCalls([
            { id: "call-1", name: "mc_status", args: "{}" },
            { id: "call-2", name: "mc_render", args: "{}" }
        ])
    ]);
    const bridge = fakeBridge({
        mc_status: async () => {
            controller.abort();
            return { text: "ok", images: [], isError: false };
        },
        mc_render: async () => {
            mcRenderCalled = true;
            return { text: "should not run", images: [], isError: false };
        }
    });

    const result = await runRequest({
        cfg: baseConfig(),
        bridge,
        history: newHistory(),
        player: player(),
        text: "build something",
        signal: controller.signal,
        onProgress: () => {},
        chatFn: fn
    });

    assert.equal(result, "Cancelled.");
    assert.equal(mcRenderCalled, false);
    assert.equal(calls.length, 1);
});

test("runRequest: an unknown tool name is handled as an error result, not a throw", async () => {
    const { fn, calls } = scriptedProvider([
        assistantToolCalls([{ id: "call-1", name: "totally_unknown_tool", args: "{}" }]),
        assistantText("I could not find that tool.")
    ]);
    const bridge = fakeBridge({
        mc_status: async () => ({ text: "ok", images: [], isError: false })
    });

    const result = await runRequest({
        cfg: baseConfig(),
        bridge,
        history: newHistory(),
        player: player(),
        text: "call something weird",
        signal: new AbortController().signal,
        onProgress: () => {},
        chatFn: fn
    });

    assert.equal(result, "I could not find that tool.");
    assert.equal(calls.length, 2);
    const toolMessage = calls[1]!.messages.find(m => m.role === "tool" && m.tool_call_id === "call-1")!;
    assert.match(String(toolMessage.content), /Unknown tool "totally_unknown_tool"/);
});

test("runRequest: progress callback reports the tool name and short args (from/to/view/action)", async () => {
    const { fn } = scriptedProvider([
        assistantToolCalls([{ id: "call-1", name: "mc_build", args: JSON.stringify({ from: [0, 0, 0], to: [1, 1, 1], block: "minecraft:stone" }) }]),
        assistantText("Built it.")
    ]);
    const bridge = fakeBridge({
        mc_build: async () => ({ text: "done", images: [], isError: false })
    });

    const progressLines: string[] = [];
    const result = await runRequest({
        cfg: baseConfig(),
        bridge,
        history: newHistory(),
        player: player(),
        text: "build a cube",
        signal: new AbortController().signal,
        onProgress: line => progressLines.push(line),
        chatFn: fn
    });

    assert.equal(result, "Built it.");
    assert.equal(progressLines.length, 1);
    assert.equal(progressLines[0], '> mc_build from=[0,0,0] to=[1,1,1]');
});

test("runRequest: no tool calls returns the trimmed assistant text directly", async () => {
    const { fn } = scriptedProvider([assistantText("  Hello there.  ")]);
    const bridge = fakeBridge({});

    const result = await runRequest({
        cfg: baseConfig(),
        bridge,
        history: newHistory(),
        player: player(),
        text: "hi",
        signal: new AbortController().signal,
        onProgress: () => {},
        chatFn: fn
    });

    assert.equal(result, "Hello there.");
});

test("runRequest: an empty assistant reply becomes '(no reply)'", async () => {
    const { fn } = scriptedProvider([assistantText("")]);
    const bridge = fakeBridge({});

    const result = await runRequest({
        cfg: baseConfig(),
        bridge,
        history: newHistory(),
        player: player(),
        text: "hi",
        signal: new AbortController().signal,
        onProgress: () => {},
        chatFn: fn
    });

    assert.equal(result, "(no reply)");
});

test("runRequest: records the exchange in history for the next request", async () => {
    const { fn } = scriptedProvider([assistantText("First reply.")]);
    const bridge = fakeBridge({});
    const history = newHistory();

    await runRequest({
        cfg: baseConfig(),
        bridge,
        history,
        player: player(),
        text: "first message",
        signal: new AbortController().signal,
        onProgress: () => {},
        chatFn: fn
    });

    const stored = history.get("p1");
    assert.equal(stored.length, 2);
    assert.equal(stored[0]!.role, "user");
    assert.equal(stored[0]!.content, "first message");
    assert.equal(stored[1]!.role, "assistant");
    assert.equal(stored[1]!.content, "First reply.");
});

test("runRequest: a cancelled request is not recorded in history", async () => {
    const controller = new AbortController();
    controller.abort();
    const { fn } = scriptedProvider([]);
    const bridge = fakeBridge({});
    const history = newHistory();

    const result = await runRequest({
        cfg: baseConfig(),
        bridge,
        history,
        player: player(),
        text: "too late",
        signal: controller.signal,
        onProgress: () => {},
        chatFn: fn
    });

    assert.equal(result, "Cancelled.");
    assert.deepEqual(history.get("p1"), []);
});
