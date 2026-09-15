#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later

// Dumps the JSON Schema tool specs every mc_* tool sends to MCP clients
// today into plugin/src/main/resources/tools/<name>.json, so the Java tool
// layer (docs/private/plan.md Step 7.2b) can load byte-identical
// name/title/description/inputSchema/annotations at runtime without
// duplicating the zod schemas or description text in Java.
//
// Zero new dependencies: uses only what mcp-server/package.json already
// depends on (@modelcontextprotocol/client, @modelcontextprotocol/server via
// dist/server.js) plus Node's own fs/path/url.
//
// Connects an in-memory MCP Client to a freshly built McpServer (same
// pattern as src/agent/tools.ts) and calls listTools(), which yields exactly
// what any MCP client sees in tools/list - the most faithful way to capture
// today's tool specs. Run after `npm run build`:
//
//   node tools/export-specs.mjs

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { Client, InMemoryTransport } from "@modelcontextprotocol/client";

import { buildServer } from "../dist/server.js";

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = join(__dirname, "..", "..", "plugin", "src", "main", "resources", "tools");

/** A PluginClient stub: export-specs never sends an actual request, it only reads tool registrations. */
const dummyClient = {
    pluginUrl: "ws://unused",
    isConnected: () => false,
    request: async () => {
        throw new Error("export-specs.mjs: request() should never be called");
    }
};

async function main() {
    const server = buildServer(dummyClient);
    const [serverTransport, clientTransport] = InMemoryTransport.createLinkedPair();
    const client = new Client({ name: "export-specs", version: "0.0.0" });
    await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);

    const { tools } = await client.listTools();
    if (tools.length === 0) {
        throw new Error("export-specs.mjs: listTools() returned no tools");
    }

    mkdirSync(OUT_DIR, { recursive: true });

    for (const tool of tools) {
        // Keys in this exact order (name, title, description, inputSchema, annotations); the MCP
        // client also sends icons/execution/_meta, which the plugin's ToolSpec does not model.
        const spec = {
            name: tool.name,
            title: tool.title,
            description: tool.description,
            inputSchema: tool.inputSchema,
            annotations: tool.annotations
        };
        const path = join(OUT_DIR, `${tool.name}.json`);
        writeFileSync(path, JSON.stringify(spec, null, 2) + "\n");
        console.log(`wrote ${path}`);
    }

    await client.close();
    await server.close();
}

main().catch(err => {
    console.error(err);
    process.exitCode = 1;
});
