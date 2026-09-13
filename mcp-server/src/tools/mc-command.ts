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

const DESCRIPTION = `Runs an arbitrary command as the server console, exactly as if an operator typed it in the console window, and returns the command's feedback text. This is an escape hatch for anything the other six tools do not cover: giving items, teleporting or managing players, changing game rules or weather/time, running datapack functions, managing whitelists/bans, and so on. It has full console privileges - the least safe tool in this set.

WHEN TO USE: only when no other tool fits - player/item/game-rule/weather/time/scoreboard/datapack administration, not "place blocks" or "read blocks/terrain". Prefer mc_build for bulk block placement even if a /fill command could do it too - mc_build is chunk-safe, tick-budgeted, and reports exactly what changed.

WHEN NOT TO USE: to place or read blocks (use mc_build / mc_inspect / mc_survey instead - purpose-built, and cannot bypass the plugin's volume/chunk/world limits). Do not use it as a workaround when mc_build rejects a request for exceeding a limit - split the build into smaller calls; the equivalent vanilla command does not remove the underlying risk (watchdog timeouts, chunk storms), it just removes the plugin's safety checks.

PARAMETERS: \`command\` is the command text, with or without a leading "/" (stripped automatically). No other parameters.

SIDE EFFECTS: unbounded - depends entirely on the command. Can modify players, world state, or server settings, unrestricted by the checks that protect mc_build. The administrator can disable this tool in config, failing every call with a clear error. Feedback sent back to the command sender is captured and returned (capped at 200 lines / 16 KB), but two things are not: feedback delivered asynchronously or after dispatch returns, and anything written only to the server log. Use mc_inspect or mc_survey afterward to verify a world-visible effect when in doubt.`;

interface RunCommandResult {
    command: string;
    dispatched: boolean;
    output: string[];
    truncated: boolean;
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
            return runTool(client, "mc_command", async () => {
                const result = (await client.request("run_command", { command })) as RunCommandResult;
                const lines: string[] = [];
                lines.push(result.output.length > 0 ? result.output.join("\n") : "(command produced no feedback)");
                if (!result.dispatched) {
                    lines.push(`Warning: command "${result.command}" was not dispatched (rejected by the server's command dispatcher).`);
                }
                if (result.truncated) {
                    lines.push("[output truncated]");
                }
                return lines.join("\n");
            });
        }
    );
}
