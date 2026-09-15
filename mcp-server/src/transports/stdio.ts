// SPDX-License-Identifier: AGPL-3.0-or-later

import { serveStdio, type StdioServerHandle } from "@modelcontextprotocol/server/stdio";

import type { PluginClient } from "../plugin-client.js";
import { buildServer, type Catalog } from "../server.js";

/** Serves MCP over stdio. All logging in this mode must go to stderr (stdout is the JSON-RPC channel). */
export function startStdio(client: PluginClient, catalog: Catalog): StdioServerHandle {
    const handle = serveStdio(() => buildServer(client, catalog));
    console.error(`ashlar-mcp[${process.pid}]: serving over stdio`);
    return handle;
}
