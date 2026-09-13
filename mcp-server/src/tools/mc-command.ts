// SPDX-License-Identifier: AGPL-3.0-or-later

import type { McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";

import type { PluginClient } from "../plugin-client.js";
import { runTool } from "./helpers.js";

const inputSchema = z.object({
    command: z
        .string()
        .min(1)
        .describe('Console command to run, with or without a leading "/" (it is stripped if present), e.g. "give Steve minecraft:diamond 1" or "weather clear".')
});

const DESCRIPTION = `Runs an arbitrary command as the server console, exactly as if an operator typed it in the console window. This is an escape hatch for anything the other six tools do not cover: giving items, teleporting or managing players, changing game rules or weather/time, running datapack functions, managing whitelists/bans, and so on. It has full console privileges - there is no command it cannot run - so it is the least safe tool in this set.

WHEN TO USE: only when no other tool fits. Managing players, items, game rules, weather, time, scoreboards, datapacks, or other server administration that is not "place blocks" or "read blocks/terrain". Prefer mc_build for anything that places, replaces, or clears blocks in bulk, even if a /fill command could do it too - mc_build is chunk-safe, tick-budgeted, and reports exactly what changed, which run_command's underlying /fill is not guaranteed to be for large volumes.

WHEN NOT TO USE: to place or read blocks (use mc_build / mc_inspect / mc_survey, which are purpose-built, report results precisely, and cannot bypass the plugin's volume/chunk/world limits). Do not use it as a workaround when mc_build rejects a request for exceeding a limit - split the build into smaller calls instead; the equivalent vanilla command does not make the underlying risk (watchdog timeouts, chunk storms) go away, it just removes the plugin's safety checks.

PARAMETERS: \`command\` is the command text, without needing a leading "/" (stripped automatically if present). There are no other parameters - this tool has no coordinate system of its own, since the command string can be anything the dispatcher accepts.

SIDE EFFECTS: unbounded - depends entirely on the command. Can modify players, world state, or server settings, and is not limited by the block-count/chunk/world-allowlist checks that protect mc_build. The administrator can disable this tool in the plugin's config; when disabled, every call fails with a clear error. Console output is not captured - only whether the command was dispatched; use mc_inspect or mc_survey afterward to verify any world-visible effect.`;

interface RunCommandResult {
    command: string;
    dispatched: boolean;
}

export function registerMcCommand(server: McpServer, client: PluginClient): void {
    server.registerTool(
        "mc_command",
        {
            title: "Run console command",
            description: DESCRIPTION,
            inputSchema,
            annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true }
        },
        async ({ command }) => {
            return runTool(client, async () => {
                const result = (await client.request("run_command", { command })) as RunCommandResult;
                const status = result.dispatched ? "dispatched" : "not dispatched (rejected by the server's command dispatcher)";
                return `Command "${result.command}" ${status}. Console output is not captured; use mc_inspect or mc_survey to verify effects.`;
            });
        }
    );
}
