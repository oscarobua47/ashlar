// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { test } from "node:test";

import { Client, InMemoryTransport } from "@modelcontextprotocol/client";

import { formatPluginError, PluginError } from "./errors.js";
import type { PluginClient } from "./plugin-client.js";
import { buildServer, fetchCatalog, PluginVersionError, type ToolSpec } from "./server.js";

const PLUGIN_URL = "ws://fake-plugin:1234";

const CATALOG: ToolSpec[] = [
    {
        name: "tool_a",
        title: "Tool A",
        description: "Does something useful with a message. WHEN TO USE: whenever a message needs doing.",
        inputSchema: {
            type: "object",
            properties: { message: { type: "string" } },
            required: ["message"],
            additionalProperties: false
        },
        annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
    },
    {
        name: "tool_b",
        title: "Tool B",
        description: "Takes no arguments at all.",
        inputSchema: { type: "object", properties: {}, additionalProperties: false },
        annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true }
    }
];

const CANNED_CONTENT = [
    { type: "text" as const, text: "hello from tool_a" },
    { type: "image" as const, data: "aGVsbG8=", mimeType: "image/png" }
];

/** A minimal `PluginClient` stand-in: only `pluginUrl` and `request` are used by server.ts. */
function fakeClient(request: (method: string, params: unknown) => Promise<unknown>): PluginClient {
    return { pluginUrl: PLUGIN_URL, isConnected: () => true, request } as unknown as PluginClient;
}

async function connectedClient(mcpServer: ReturnType<typeof buildServer>): Promise<Client> {
    const [serverTransport, clientTransport] = InMemoryTransport.createLinkedPair();
    const client = new Client({ name: "server-test", version: "0.0.0" });
    await Promise.all([mcpServer.connect(serverTransport), client.connect(clientTransport)]);
    return client;
}

test("buildServer: registers every catalog tool with its title/description/schema/annotations", async () => {
    const plugin = fakeClient(async () => {
        throw new Error("request should not be called while just listing tools");
    });
    const server = buildServer(plugin, { tools: CATALOG });
    const client = await connectedClient(server);
    try {
        const { tools } = await client.listTools();
        assert.equal(tools.length, 2);

        const a = tools.find(t => t.name === "tool_a");
        const b = tools.find(t => t.name === "tool_b");
        assert.ok(a, "tool_a is registered");
        assert.ok(b, "tool_b is registered");

        assert.equal(a!.title, "Tool A");
        assert.equal(a!.description, CATALOG[0]!.description);
        assert.equal(a!.inputSchema.type, "object");
        assert.deepEqual(Object.keys((a!.inputSchema as { properties?: Record<string, unknown> }).properties ?? {}), ["message"]);
        assert.deepEqual(a!.annotations, CATALOG[0]!.annotations);

        assert.equal(b!.title, "Tool B");
        assert.deepEqual(b!.annotations, CATALOG[1]!.annotations);
    } finally {
        await client.close();
        await server.close();
    }
});

test("buildServer: callTool forwards name/args to tool_call and passes the content through untouched", async () => {
    const calls: Array<{ name: string; args: unknown }> = [];
    const plugin = fakeClient(async (method, params) => {
        assert.equal(method, "tool_call");
        calls.push(params as { name: string; args: unknown });
        return { content: CANNED_CONTENT, isError: false };
    });
    const server = buildServer(plugin, { tools: CATALOG });
    const client = await connectedClient(server);
    try {
        const result = await client.callTool({ name: "tool_a", arguments: { message: "hi" } });

        assert.equal(result.isError, false);
        const content = result.content as Array<{ type: string; text?: string; data?: string; mimeType?: string }>;
        assert.equal(content.length, 2);
        assert.equal(content[0]?.type, "text");
        assert.equal(content[0]?.text, "hello from tool_a");
        assert.equal(content[1]?.type, "image");
        assert.equal(content[1]?.data, "aGVsbG8=");
        assert.equal(content[1]?.mimeType, "image/png");

        assert.equal(calls.length, 1);
        assert.equal(calls[0]!.name, "tool_a");
        assert.deepEqual(calls[0]!.args, { message: "hi" });
    } finally {
        await client.close();
        await server.close();
    }
});

test("buildServer: a plugin-supplied isError result passes through untouched", async () => {
    const plugin = fakeClient(async () => ({
        content: [{ type: "text", text: "Requested volume exceeds the limit." }],
        isError: true
    }));
    const server = buildServer(plugin, { tools: CATALOG });
    const client = await connectedClient(server);
    try {
        const result = await client.callTool({ name: "tool_b", arguments: {} });
        assert.equal(result.isError, true);
        const content = result.content as Array<{ type: string; text?: string }>;
        assert.equal(content[0]?.text, "Requested volume exceeds the limit.");
    } finally {
        await client.close();
        await server.close();
    }
});

test("buildServer: a PluginError thrown by the tool_call request becomes an isError text result with the formatted wording", async () => {
    const err = new PluginError("TIMEOUT", 'plugin did not respond to "tool_call" within 500ms');
    const plugin = fakeClient(async () => {
        throw err;
    });
    const server = buildServer(plugin, { tools: CATALOG });
    const client = await connectedClient(server);
    try {
        const result = await client.callTool({ name: "tool_b", arguments: {} });
        assert.equal(result.isError, true);
        const content = result.content as Array<{ type: string; text?: string }>;
        assert.equal(content.length, 1);
        assert.equal(content[0]?.text, formatPluginError(err, PLUGIN_URL));
    } finally {
        await client.close();
        await server.close();
    }
});

test("fetchCatalog: returns the tools from a successful tool_catalog call", async () => {
    const plugin = fakeClient(async method => {
        assert.equal(method, "tool_catalog");
        return { tools: CATALOG, instructions: "Use these tools." };
    });
    const catalog = await fetchCatalog(plugin);
    assert.deepEqual(catalog.tools, CATALOG);
    assert.equal(catalog.instructions, "Use these tools.");
});

test("buildServer: plugin-authored instructions are used verbatim; without them a generic list is generated", async () => {
    const plugin = fakeClient(async () => ({ content: [], isError: false }));
    const withText = buildServer(plugin, { tools: CATALOG, instructions: "Use these tools." });
    const without = buildServer(plugin, { tools: CATALOG });
    // The McpServer exposes the instructions through its initialize result; connect a client to read them.
    for (const [server, expectVerbatim] of [[withText, true], [without, false]] as const) {
        const [clientT, serverT] = InMemoryTransport.createLinkedPair();
        await server.connect(serverT);
        const client = new Client({ name: "t", version: "0" });
        await client.connect(clientT);
        const text = client.getInstructions() ?? "";
        if (expectVerbatim) assert.equal(text, "Use these tools.");
        else {
            assert.match(text, /tools are available/);
            assert.ok(!/Typical flow/.test(text));
        }
        await client.close();
    }
});

test("fetchCatalog: UNKNOWN_METHOD on tool_catalog raises PluginVersionError with the version-requirement message", async () => {
    const plugin = fakeClient(async () => {
        throw new PluginError("UNKNOWN_METHOD", "unknown method: tool_catalog");
    });
    await assert.rejects(
        () => fetchCatalog(plugin),
        (err: unknown) => {
            assert.ok(err instanceof PluginVersionError);
            assert.match((err as Error).message, /^ashlar-mcp \S+ requires Ashlar plugin >= \S+ \(tool_catalog RPC not found\); upgrade the plugin$/);
            return true;
        }
    );
});

test("fetchCatalog: any other error propagates unchanged", async () => {
    const plugin = fakeClient(async () => {
        throw new PluginError("UNAVAILABLE", "cannot reach the plugin");
    });
    await assert.rejects(
        () => fetchCatalog(plugin),
        (err: unknown) => {
            assert.ok(err instanceof PluginError);
            assert.equal((err as PluginError).code, "UNAVAILABLE");
            return true;
        }
    );
});
