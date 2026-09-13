// SPDX-License-Identifier: AGPL-3.0-or-later

import { McpServer } from "@modelcontextprotocol/server";

import type { PluginClient } from "./plugin-client.js";
import { registerAllTools } from "./tools/index.js";

const SERVER_NAME = "mc-ai-builder-mcp";
const SERVER_VERSION = "0.1.0";

/**
 * Builds a fresh `McpServer` instance with every mc_* tool registered
 * against the shared, module-scoped `client`. Called once per stdio
 * connection and once per HTTP request (spec/plan section 4.0: "long-lived
 * state must live in module scope, not inside this factory").
 */
export function buildServer(client: PluginClient): McpServer {
    const server = new McpServer({ name: SERVER_NAME, version: SERVER_VERSION });
    registerAllTools(server, client);
    return server;
}
