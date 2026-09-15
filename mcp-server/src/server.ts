// SPDX-License-Identifier: AGPL-3.0-or-later

import { fromJsonSchema, McpServer, type CallToolResult } from "@modelcontextprotocol/server";

import { formatPluginError, PluginError } from "./errors.js";
import type { PluginClient } from "./plugin-client.js";
import type { ContentBlock } from "./tools/helpers.js";
import { logUsage } from "./usage-log.js";

const SERVER_NAME = "ashlar-mcp";
export const SERVER_VERSION = "0.4.3";
export const MIN_PLUGIN_VERSION = "0.3.0";

/**
 * One `mc_*` tool's client-facing definition, exactly as the plugin's
 * `tool_catalog` RPC sends it (mirrors the plugin's `ToolSpec` record). The
 * Node package holds no Ashlar tool knowledge of its own: every field here
 * comes verbatim from the plugin and is registered as-is.
 */
export interface ToolSpec {
    name: string;
    title: string;
    description: string;
    inputSchema: Record<string, unknown>;
    annotations?: Record<string, unknown>;
}

/** Shape of the plugin's `tool_catalog` RPC result: `{"tools": [...]}`. */
export interface ToolCatalogResult {
    tools: ToolSpec[];
    instructions?: string;
}

/** What `tool_catalog` returns: the tool specs plus the plugin-authored server-level instructions text. */
export interface Catalog {
    tools: ToolSpec[];
    instructions?: string;
}

/** Shape of the plugin's `tool_call` RPC result: mirrors the plugin's `ToolResult` record. */
export interface ToolCallRpcResult {
    content: ContentBlock[];
    isError: boolean;
}

/**
 * Thrown by {@link fetchCatalog} when the plugin rejects `tool_catalog` with
 * `UNKNOWN_METHOD` - i.e. it predates step 7.2b and has no tool_catalog RPC
 * at all. `message` is the exact, actionable text printed to stderr in every
 * mode (--stdio, --http) before exiting 2.
 */
export class PluginVersionError extends Error {
    constructor() {
        super(`ashlar-mcp ${SERVER_VERSION} requires Ashlar plugin >= ${MIN_PLUGIN_VERSION} (tool_catalog RPC not found); upgrade the plugin`);
        this.name = "PluginVersionError";
    }
}

/**
 * Fetches the plugin's tool catalog (spec/plan section 4.0: "on connect it
 * asks the plugin for the catalog and registers whatever comes back").
 * Throws {@link PluginVersionError} when the plugin is too old to answer
 * `tool_catalog`; any other error (a connection failure, a timeout, ...)
 * propagates unchanged.
 */
export async function fetchCatalog(client: PluginClient): Promise<Catalog> {
    try {
        const result = (await client.request("tool_catalog", {})) as ToolCatalogResult;
        return { tools: result.tools, instructions: result.instructions };
    } catch (err) {
        if (err instanceof PluginError && err.code === "UNKNOWN_METHOD") {
            throw new PluginVersionError();
        }
        throw err;
    }
}

/** Returns `description` up to and including its first period, or the whole (trimmed) text if it has none. */
function firstSentence(description: string): string {
    const idx = description.indexOf(".");
    return idx === -1 ? description.trim() : description.slice(0, idx + 1).trim();
}

/**
 * Server-level `INSTRUCTIONS` text: the plugin-authored `instructions` from
 * the catalog when present (the normal case), otherwise a generic fallback
 * built from the catalog alone - one line per tool, `<name> - <title>: <first sentence of description>`, name
 * column padded for readability. Clients show these to the model as soon as
 * the connector is enabled, even when they load tool definitions lazily
 * (Claude Desktop's "Load tools when needed"), so the model knows every tool
 * that exists and can search for it by exact name. If a generated line reads
 * badly, fix the tool's `title` in the plugin's JSON resources, not here -
 * this function must stay free of any Ashlar-specific tool knowledge.
 */
function buildInstructions(catalog: Catalog): string {
    if (catalog.instructions && catalog.instructions.trim()) {
        return catalog.instructions.trim();
    }
    const tools = catalog.tools;
    const nameWidth = Math.max(0, ...tools.map(spec => spec.name.length));
    const lines = tools.map(spec => `${spec.name.padEnd(nameWidth)} - ${spec.title}: ${firstSentence(spec.description)}`);
    return `${tools.length} tools are available - load them by name when needed:\n\n${lines.join("\n")}`;
}

/**
 * Builds a fresh `McpServer` instance with every tool from `catalog`
 * registered against the shared, module-scoped `client`. Called once per
 * stdio connection and once per HTTP request (spec/plan section 4.0:
 * "long-lived state must live in module scope, not inside this factory").
 *
 * Every tool's handler forwards the call verbatim to the plugin's
 * `tool_call` RPC and passes the returned content blocks through untouched -
 * this package carries no per-tool knowledge of its own. A `PluginError`
 * thrown by the RPC call itself (a transport/connection failure, not a
 * world-facing error the plugin already formatted into its `ToolResult`)
 * becomes an `isError` text result using {@link formatPluginError}; this is
 * the only text formatting left on the Node side, and it concerns the
 * connection, not the world.
 */
export function buildServer(client: PluginClient, catalog: Catalog): McpServer {
    const server = new McpServer({ name: SERVER_NAME, version: SERVER_VERSION }, { instructions: buildInstructions(catalog) });

    for (const spec of catalog.tools) {
        server.registerTool(
            spec.name,
            {
                title: spec.title,
                description: spec.description,
                inputSchema: fromJsonSchema(spec.inputSchema as Parameters<typeof fromJsonSchema>[0]),
                annotations: spec.annotations
            },
            async (args: unknown): Promise<CallToolResult> => {
                const startedAt = Date.now();
                try {
                    const result = (await client.request("tool_call", { name: spec.name, args })) as ToolCallRpcResult;
                    logUsage(spec.name, startedAt, result.content);
                    return { content: result.content, isError: result.isError } as CallToolResult;
                } catch (err) {
                    if (err instanceof PluginError) {
                        return { content: [{ type: "text", text: formatPluginError(err, client.pluginUrl) }], isError: true };
                    }
                    const message = err instanceof Error ? err.message : String(err);
                    return { content: [{ type: "text", text: message }], isError: true };
                }
            }
        );
    }

    return server;
}
