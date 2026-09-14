// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { test } from "node:test";

import type { AgentConfig } from "./config.js";
import { parsePeakHours } from "./pricing.js";
import { chatCompletion, ProviderError } from "./provider.js";

function baseConfig(baseUrl: string): AgentConfig {
    return {
        baseUrl,
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
        usageFile: "./ashlar-usage.json",
        priceInput: 0.3,
        priceCachedInput: 0.006,
        priceOutput: 1.2,
        currency: "USD",
        maxTokensPerPlayerPerDay: 0,
        maxCostPerPlayerPerDay: 0,
        peakHours: parsePeakHours("mon-fri 01:00-04:00,06:00-10:00"),
        offPeakMultiplier: 0.5
    };
}

function readBody(req: IncomingMessage): Promise<string> {
    return new Promise((resolve, reject) => {
        let data = "";
        req.on("data", chunk => (data += chunk));
        req.on("end", () => resolve(data));
        req.on("error", reject);
    });
}

/** Starts an http server whose handler runs once per request (1-indexed `count`); returns {url, close}. */
async function startServer(
    handler: (req: IncomingMessage, res: ServerResponse, count: number) => void | Promise<void>
): Promise<{ url: string; close: () => Promise<void> }> {
    let count = 0;
    const server: Server = createServer((req, res) => {
        count++;
        void handler(req, res, count);
    });
    await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    const port = typeof address === "object" && address !== null ? address.port : 0;
    return {
        url: `http://127.0.0.1:${port}`,
        close: () => new Promise<void>(resolve => server.close(() => resolve()))
    };
}

test("chatCompletion: a tool_calls reply, then a final text reply", async () => {
    const { url, close } = await startServer(async (req, res, count) => {
        await readBody(req);
        res.writeHead(200, { "content-type": "application/json" });
        if (count === 1) {
            res.end(
                JSON.stringify({
                    choices: [
                        {
                            message: {
                                role: "assistant",
                                content: null,
                                tool_calls: [{ id: "call-1", type: "function", function: { name: "mc_status", arguments: "{}" } }]
                            },
                            finish_reason: "tool_calls"
                        }
                    ],
                    usage: { prompt_tokens: 10, completion_tokens: 5 }
                })
            );
        } else {
            res.end(
                JSON.stringify({
                    choices: [{ message: { role: "assistant", content: "Done." }, finish_reason: "stop" }],
                    usage: { prompt_tokens: 20, completion_tokens: 3 }
                })
            );
        }
    });
    try {
        const cfg = baseConfig(url);
        const first = await chatCompletion(cfg, { messages: [{ role: "user", content: "hi" }], tools: [] });
        assert.equal(first.message.tool_calls?.length, 1);
        assert.equal(first.message.tool_calls?.[0]!.function.name, "mc_status");
        assert.equal(first.finishReason, "tool_calls");

        const second = await chatCompletion(cfg, { messages: [{ role: "user", content: "hi" }], tools: [] });
        assert.equal(second.message.content, "Done.");
        assert.equal(second.finishReason, "stop");
        assert.equal(second.usage.inputTokens, 20);
        assert.equal(second.usage.cachedInputTokens, 0);
        assert.equal(second.usage.outputTokens, 3);
    } finally {
        await close();
    }
});

test("chatCompletion: normalises DeepSeek's prompt_cache_hit_tokens field", async () => {
    const { url, close } = await startServer(async (req, res) => {
        await readBody(req);
        res.writeHead(200, { "content-type": "application/json" });
        res.end(
            JSON.stringify({
                choices: [{ message: { role: "assistant", content: "ok" }, finish_reason: "stop" }],
                usage: { prompt_tokens: 100, prompt_cache_hit_tokens: 60, prompt_cache_miss_tokens: 40, completion_tokens: 10 }
            })
        );
    });
    try {
        const cfg = baseConfig(url);
        const result = await chatCompletion(cfg, { messages: [{ role: "user", content: "hi" }], tools: [] });
        assert.equal(result.usage.cachedInputTokens, 60);
        assert.equal(result.usage.inputTokens, 40);
        assert.equal(result.usage.outputTokens, 10);
    } finally {
        await close();
    }
});

test("chatCompletion: normalises the OpenAI-style prompt_tokens_details.cached_tokens field", async () => {
    const { url, close } = await startServer(async (req, res) => {
        await readBody(req);
        res.writeHead(200, { "content-type": "application/json" });
        res.end(
            JSON.stringify({
                choices: [{ message: { role: "assistant", content: "ok" }, finish_reason: "stop" }],
                usage: { prompt_tokens: 100, prompt_tokens_details: { cached_tokens: 25 }, completion_tokens: 10 }
            })
        );
    });
    try {
        const cfg = baseConfig(url);
        const result = await chatCompletion(cfg, { messages: [{ role: "user", content: "hi" }], tools: [] });
        assert.equal(result.usage.cachedInputTokens, 25);
        assert.equal(result.usage.inputTokens, 75);
        assert.equal(result.usage.outputTokens, 10);
    } finally {
        await close();
    }
});

test("chatCompletion: missing cache fields are treated as zero cached", async () => {
    const { url, close } = await startServer(async (req, res) => {
        await readBody(req);
        res.writeHead(200, { "content-type": "application/json" });
        res.end(
            JSON.stringify({
                choices: [{ message: { role: "assistant", content: "ok" }, finish_reason: "stop" }],
                usage: { prompt_tokens: 50, completion_tokens: 5 }
            })
        );
    });
    try {
        const cfg = baseConfig(url);
        const result = await chatCompletion(cfg, { messages: [{ role: "user", content: "hi" }], tools: [] });
        assert.equal(result.usage.cachedInputTokens, 0);
        assert.equal(result.usage.inputTokens, 50);
        assert.equal(result.usage.outputTokens, 5);
    } finally {
        await close();
    }
});

test("chatCompletion: a 500 then a 200 succeeds via retry", async () => {
    const { url, close } = await startServer(async (req, res, count) => {
        await readBody(req);
        if (count === 1) {
            res.writeHead(500, { "content-type": "text/plain" });
            res.end("server error");
            return;
        }
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ choices: [{ message: { role: "assistant", content: "ok" }, finish_reason: "stop" }], usage: {} }));
    });
    try {
        const cfg = baseConfig(url);
        const result = await chatCompletion(cfg, { messages: [{ role: "user", content: "hi" }], tools: [] });
        assert.equal(result.message.content, "ok");
    } finally {
        await close();
    }
});

test("chatCompletion: a 400 throws ProviderError with a body excerpt, no retry", async () => {
    let requestCount = 0;
    const { url, close } = await startServer(async (req, res) => {
        requestCount++;
        await readBody(req);
        res.writeHead(400, { "content-type": "text/plain" });
        res.end("bad request: invalid model name given");
    });
    try {
        const cfg = baseConfig(url);
        await assert.rejects(
            () => chatCompletion(cfg, { messages: [{ role: "user", content: "hi" }], tools: [] }),
            (err: unknown) => {
                assert.ok(err instanceof ProviderError);
                assert.equal(err.status, 400);
                assert.match(err.message, /bad request: invalid model name given/);
                return true;
            }
        );
        assert.equal(requestCount, 1, "a 400 must not be retried");
    } finally {
        await close();
    }
});

test("chatCompletion: an aborted signal rejects promptly", async () => {
    const { url, close } = await startServer(async () => {
        // Never respond; the abort, not the server, must settle the request.
        await new Promise(() => {});
    });
    try {
        const cfg = baseConfig(url);
        const controller = new AbortController();
        const promise = chatCompletion(cfg, { messages: [{ role: "user", content: "hi" }], tools: [], signal: controller.signal });
        const startedAt = Date.now();
        controller.abort();
        await assert.rejects(promise);
        const elapsedMs = Date.now() - startedAt;
        assert.ok(elapsedMs < 2000, `abort should reject promptly, took ${elapsedMs}ms`);
    } finally {
        await close();
    }
});

test("chatCompletion: omits the tools field from the request body when tools is empty", async () => {
    let sawTools = true;
    const { url, close } = await startServer(async (req, res) => {
        const raw = await readBody(req);
        const body = JSON.parse(raw);
        sawTools = Object.prototype.hasOwnProperty.call(body, "tools");
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ choices: [{ message: { role: "assistant", content: "ok" } }], usage: {} }));
    });
    try {
        const cfg = baseConfig(url);
        await chatCompletion(cfg, { messages: [{ role: "user", content: "hi" }], tools: [] });
        assert.equal(sawTools, false);
    } finally {
        await close();
    }
});
