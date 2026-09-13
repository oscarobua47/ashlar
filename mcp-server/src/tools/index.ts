// SPDX-License-Identifier: AGPL-3.0-or-later

import type { McpServer } from "@modelcontextprotocol/server";

import type { PluginClient } from "../plugin-client.js";
import { registerMcBuild } from "./mc-build.js";
import { registerMcCommand } from "./mc-command.js";
import { registerMcInspect } from "./mc-inspect.js";
import { registerMcPlayers } from "./mc-players.js";
import { registerMcRestore } from "./mc-restore.js";
import { registerMcSnapshot } from "./mc-snapshot.js";
import { registerMcStatus } from "./mc-status.js";
import { registerMcSurvey } from "./mc-survey.js";

/** Registers all eight mc_* tools on a fresh `McpServer` instance, backed by the shared plugin connection. */
export function registerAllTools(server: McpServer, client: PluginClient): void {
    registerMcStatus(server, client);
    registerMcPlayers(server, client);
    registerMcSurvey(server, client);
    registerMcBuild(server, client);
    registerMcInspect(server, client);
    registerMcSnapshot(server, client);
    registerMcRestore(server, client);
    registerMcCommand(server, client);
}
