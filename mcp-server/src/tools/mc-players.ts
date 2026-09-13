// SPDX-License-Identifier: AGPL-3.0-or-later

import type { McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";

import type { PluginClient } from "../plugin-client.js";
import { runTool } from "./helpers.js";

interface PlayerInfo {
    name: string;
    uuid: string;
    world: string;
    pos: [number, number, number];
    exact: [number, number, number];
    yaw: number;
    pitch: number;
    facing: string;
    inFront: [number, number, number];
    gameMode: string;
    flying: boolean;
    health: number;
}

interface PlayersResult {
    count: number;
    players: PlayerInfo[];
}

const DESCRIPTION = `Lists every player currently online, with exact position, block position, facing direction, and status, giving an AI the spatial context needed to place a build relative to a player instead of guessing coordinates. Calls the plugin's "players" RPC, which reads Bukkit.getOnlinePlayers() on the main thread.

WHEN TO USE: whenever a request depends on where a player is or is looking. Phrases like "here", "where I am", "in front of me", or "at my feet" all mean "call mc_players first and use its pos/inFront values" - the AI has no other way to learn a player's location. Also call it before a build that should avoid burying or trapping a player, or to answer "who is online right now".

WHEN NOT TO USE: for terrain or block data unrelated to a player's position (use mc_survey for surface height, mc_inspect for block contents). Do not call it repeatedly while waiting for a player to move; call it once and reuse the coordinates.

PARAMETERS: none. This tool takes no arguments and always returns every online player.

SIDE EFFECTS: none - purely read-only and safe to call at any time; it never modifies the world. For each player, \`pos\` is the block the player's feet occupy; the block under the player is y-1, so filling \`pos\` itself when asked to build "at my feet" would place a block inside the player, not under them - build at y-1, or at \`inFront\` (\`pos\` shifted one block toward the direction the player is facing) for "in front of me" requests instead. \`exact\` is the unrounded coordinate (2 decimal places); \`facing\` is one of south/west/north/east derived from yaw; \`gameMode\`, \`flying\`, and \`health\` describe the player's current status. Returns "No players online." when nobody is connected.`;

function formatPlayer(p: PlayerInfo): string {
    const [x, y, z] = p.pos;
    const [fx, fy, fz] = p.inFront;
    return `${p.name}  ${p.world}  pos x=${x} y=${y} z=${z}  facing ${p.facing} (block in front: ${fx},${fy},${fz})  ${p.gameMode.toLowerCase()}`;
}

export function registerMcPlayers(server: McpServer, client: PluginClient): void {
    server.registerTool(
        "mc_players",
        {
            title: "List online players",
            description: DESCRIPTION,
            inputSchema: z.object({}),
            annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
        },
        async () => {
            return runTool(client, async () => {
                const result = (await client.request("players", {})) as PlayersResult;
                if (result.count === 0) {
                    return "No players online.";
                }
                return result.players.map(formatPlayer).join("\n");
            });
        }
    );
}
