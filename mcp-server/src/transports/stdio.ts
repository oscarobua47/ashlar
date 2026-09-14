// SPDX-License-Identifier: AGPL-3.0-or-later

import { serveStdio, type StdioServerHandle } from "@modelcontextprotocol/server/stdio";

import type { PluginClient } from "../plugin-client.js";
import { buildServer } from "../server.js";

/** Serves MCP over stdio. All logging in this mode must go to stderr (stdout is the JSON-RPC channel). */
export function startStdio(client: PluginClient): StdioServerHandle {
    const handle = serveStdio(() => buildServer(client));
    console.error(`ashlar-mcp[${process.pid}]: serving over stdio`);
    return handle;
}
