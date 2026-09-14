// SPDX-License-Identifier: AGPL-3.0-or-later

import type { McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";

import { PluginError } from "../errors.js";
import type { PluginClient } from "../plugin-client.js";
import { runTool } from "./helpers.js";
import { formatWarnings, type SupportWarning } from "./warnings.js";

const fillModeEnum = z.enum(["replace", "keep", "outline", "hollow", "walls"]);

/**
 * Orientation rules for the block states an AI most often gets wrong
 * (observed in real sessions: floating ladders, doors flush with the wrong
 * face). Attached to the `block` field descriptions so the model sees them
 * exactly when it writes a block state string.
 */
const ORIENTATION_RULES =
    " Orientation rules: for attached blocks (ladder, wall_torch, wall_sign, wall_banner, lever/button with face=wall) " +
    "`facing` points AWAY from the supporting block - a ladder on the west wall of a room is ladder[facing=east]. " +
    "Doors: the panel sits flush with the block face OPPOSITE to `facing`, so a door in a south wall that should be flush " +
    "with the outside uses facing=north; place half=lower at y and half=upper at y+1 with identical other properties; " +
    "hinge=left/right chooses the swing side. Stairs: the tall half is on the `facing` side (you walk up toward `facing`); " +
    "half=top for upside-down stairs. Beds: part=foot at pos, part=head one block toward `facing`. Chests/furnaces: " +
    "`facing` is the side the front is on. Slabs: type=bottom|top|double. Torches: `torch` stands on a solid block " +
    "below; a torch on a wall is `wall_torch[facing=<away from the wall>]` placed in the air block beside the wall - " +
    "never replace a wall block with a torch. Same for `soul_torch`/`redstone_torch`.";

const fillOpSchema = z.object({
    from: z.tuple([z.number().int(), z.number().int(), z.number().int()]).describe("Inclusive [x, y, z] corner."),
    to: z
        .tuple([z.number().int(), z.number().int(), z.number().int()])
        .describe("Inclusive [x, y, z] corner opposite `from`. Order does not matter."),
    block: z
        .string()
        .min(1)
        .describe(
            'Block state string in the "minecraft:" namespace, optionally with properties, ' +
                'e.g. "minecraft:stone" or "minecraft:oak_log[axis=y]".' +
                ORIENTATION_RULES
        ),
    mode: fillModeEnum
        .optional()
        .describe(
            '"replace" (default) overwrites everything; "keep" only fills air; "outline" places only the 1-block shell on all six ' +
                'faces; "hollow" places that shell and clears the inside; "walls" places only the four vertical sides (no floor or ceiling) ' +
                'and leaves the inside untouched - use "walls" for rooms and buildings, then add a floor and a roof with separate fills. ' +
                'On natural terrain, clear the interior with an "air" fill first: "walls" does not remove grass, flowers, snow or dirt inside.'
        ),
    filter: z
        .string()
        .optional()
        .describe(
            'Only replace cells whose current block matches this state string (unspecified properties act as a wildcard), ' +
                'e.g. "minecraft:air" to build only into empty space.'
        )
});



const signSchema = z.object({
    front: z
        .array(z.string().max(64))
        .min(1)
        .max(4)
        .optional()
        .describe('Up to 4 lines of text for the sign\'s front side, top to bottom. Missing lines are left blank. Each line max 64 characters.'),
    back: z
        .array(z.string().max(64))
        .min(1)
        .max(4)
        .optional()
        .describe('Up to 4 lines of text for the sign\'s back side, top to bottom. Missing lines are left blank. Each line max 64 characters.'),
    color: z
        .string()
        .optional()
        .describe('Dye color name for the text, e.g. "black" (default) or "red". Applies to whichever of front/back is given.'),
    glowing: z.boolean().optional().describe("Whether the text has the glow-ink-sac glowing effect. Default false."),
    waxed: z.boolean().optional().describe("Whether the sign is waxed (can no longer be edited by right-clicking with a dye). Default false.")
});

const sparseOpSchema = z.object({
    pos: z.tuple([z.number().int(), z.number().int(), z.number().int()]).describe("Absolute [x, y, z] position."),
    block: z
        .string()
        .min(1)
        .describe(
            'Block state string in the "minecraft:" namespace, with properties for orientation where relevant, ' +
                'e.g. "minecraft:oak_stairs[facing=north,half=bottom]".' +
                ORIENTATION_RULES
        ),
    sign: signSchema
        .optional()
        .describe(
            'Sign text/appearance to write onto this entry\'s block, which must be a sign (block name ending in "_sign" or ' +
                '"_hanging_sign"). At least one of front/back must be given.'
        )
});

const inputSchema = z
    .object({
        world: z.string().min(1).optional().describe("World name. Omitted uses the plugin's configured default world."),
        fills: z
            .array(fillOpSchema)
            .optional()
            .describe("Cuboid fill operations, executed in array order via the plugin's fill_batch RPC."),
        blocks: z
            .array(sparseOpSchema)
            .optional()
            .describe("Individual block placements, executed in array order via the plugin's set_blocks RPC."),
        snapshot: z
            .boolean()
            .optional()
            .describe(
                "When true, snapshot the bounding box of all fills/blocks before building, so mc_restore can undo this call. Default: false."
            ),
        connect: z
            .boolean()
            .optional()
            .describe(
                "Whether connectable blocks (glass panes, fences, walls, iron bars, stairs, redstone wire) get a shape-only update " +
                    "after placing so they connect to their neighbours. Default: true. Set false to skip this pass (e.g. for speed on a " +
                    "very large batch, or to intentionally leave a disconnected placeholder shape)."
            )
    })
    .refine(v => (v.fills && v.fills.length > 0) || (v.blocks && v.blocks.length > 0), {
        message: '"fills" and/or "blocks" must be provided, with at least one non-empty'
    });

const DESCRIPTION = `Places blocks in the Minecraft world in bulk: cuboid fill operations and/or individual block placements, up to 500,000 blocks per call. Executes asynchronously across ticks, so large builds do not lag players.

WHEN TO USE: any task that places more than a handful of blocks - buildings, terrain shaping, clearing space, roads, walls. Prefer few large \`fills\`. Use \`blocks\` for details that need a specific orientation or state (stairs, doors, torches, signs): pass the full block state string, e.g. \`minecraft:oak_stairs[facing=north,half=bottom]\`.
WHEN NOT TO USE: to read the world (use \`mc_survey\` before building and \`mc_inspect\` after); to run a server command (use \`mc_command\`).

COORDINATES: X grows east, Z grows south, Y grows up. \`from\` and \`to\` are inclusive corners in any order. Volume = (x2-x1+1)*(y2-y1+1)*(z2-z1+1). Operations run in array order - put clearing (\`minecraft:air\`) before filling. \`mode\`: "replace" (default) overwrites everything; "keep" fills only air; "outline"/"hollow" place a 1-block shell (hollow also clears the inside); "walls" places only the four vertical sides - use it for rooms, then add a floor and roof separately. \`filter\` restricts a fill to blocks matching that state (e.g. \`filter: "minecraft:air"\` builds only into empty space). Blocks are placed without physics (sand/gravel do not fall, water does not flow); connectable blocks (panes, fences, walls, bars, stairs) get a shape-only update afterwards so they connect like hand-placed blocks. To write a sign, pass a \`sign\` object on a \`blocks\` entry whose block is a sign, e.g. \`minecraft:oak_wall_sign[facing=south]\` in the air block in front of the wall - never replace the wall block itself.

SIDE EFFECTS: permanently modifies the world. Set \`snapshot: true\` (recommended for anything you might want to undo) to save the affected region first; the response then includes a snapshot id for \`mc_restore\`. Limits per call: 500,000 blocks total, 1,024 chunks footprint, one world. Requests that exceed a limit are rejected before anything changes. Typical time: 50,000 blocks in about 1-3 seconds. The response lists WARNINGS for unsupported blocks (ladders, torches, signs, doors, carpets...); fix those before reporting the build done.`;

interface FillBatchResult {
    world: string;
    ops: Array<{ index: number; volume: number; changed: number }>;
    totalVolume: number;
    totalChanged: number;
    queuedMs: number;
    elapsedMs: number;
    warnings: SupportWarning[];
    warningsTruncated?: boolean;
}

interface SetBlocksResult {
    world: string;
    requested: number;
    changed: number;
    queuedMs: number;
    elapsedMs: number;
    warnings: SupportWarning[];
    warningsTruncated?: boolean;
}

interface SnapshotResult {
    id: string;
    world: string;
    from: [number, number, number];
    to: [number, number, number];
    volume: number;
    createdAt: string;
}

export function registerMcBuild(server: McpServer, client: PluginClient): void {
    server.registerTool(
        "mc_build",
        {
            title: "Build",
            description: DESCRIPTION,
            inputSchema,
            annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false }
        },
        async ({ world, fills, blocks, snapshot, connect }) => {
            return runTool(client, "mc_build", async () => {
                const fillList = fills ?? [];
                const blockList = blocks ?? [];
                const lines: string[] = [];
                const allWarnings: SupportWarning[] = [];
                let warningsTruncated = false;

                if (snapshot) {
                    const box = boundingBox(fillList, blockList);
                    try {
                        const snap = (await client.request("snapshot", {
                            world,
                            from: box.from,
                            to: box.to,
                            label: "mc_build auto-snapshot"
                        })) as SnapshotResult;
                        lines.push(
                            `Snapshot ${snap.id} created (volume ${snap.volume}) before building; call mc_restore({"id":"${snap.id}"}) to undo this build.`
                        );
                    } catch (err) {
                        if (err instanceof PluginError && err.code === "VOLUME_EXCEEDED") {
                            throw new Error(
                                `${err.message} The bounding box of this build's fills/blocks is too large to snapshot. Retry with "snapshot": false, or split the build into smaller mc_build calls so each one's snapshot region stays under the limit.`
                            );
                        }
                        throw err;
                    }
                }

                if (fillList.length > 0) {
                    const result = (await client.request("fill_batch", {
                        world,
                        ops: fillList,
                        connect
                    })) as FillBatchResult;
                    lines.push("Fills:");
                    for (const op of result.ops) {
                        const spec = fillList[op.index]!;
                        lines.push(
                            `  #${op.index} [${spec.from.join(",")}] -> [${spec.to.join(",")}] ${spec.block}: ${op.changed}/${op.volume} changed`
                        );
                    }
                    lines.push(`  total: ${result.totalChanged}/${result.totalVolume} changed in ${result.elapsedMs}ms`);
                    allWarnings.push(...result.warnings);
                    warningsTruncated = warningsTruncated || result.warningsTruncated === true;
                }

                if (blockList.length > 0) {
                    const result = (await client.request("set_blocks", {
                        world,
                        blocks: blockList,
                        connect
                    })) as SetBlocksResult;
                    lines.push(`Blocks: ${result.changed}/${result.requested} changed in ${result.elapsedMs}ms`);
                    allWarnings.push(...result.warnings);
                    warningsTruncated = warningsTruncated || result.warningsTruncated === true;
                }

                lines.push(...formatWarnings(allWarnings, warningsTruncated));

                return lines.join("\n");
            });
        }
    );
}

function boundingBox(
    fills: Array<{ from: [number, number, number]; to: [number, number, number] }>,
    blocks: Array<{ pos: [number, number, number] }>
): { from: [number, number, number]; to: [number, number, number] } {
    let minX = Infinity,
        minY = Infinity,
        minZ = Infinity;
    let maxX = -Infinity,
        maxY = -Infinity,
        maxZ = -Infinity;

    const consider = (x: number, y: number, z: number) => {
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (z < minZ) minZ = z;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
        if (z > maxZ) maxZ = z;
    };

    for (const f of fills) {
        consider(f.from[0], f.from[1], f.from[2]);
        consider(f.to[0], f.to[1], f.to[2]);
    }
    for (const b of blocks) {
        consider(b.pos[0], b.pos[1], b.pos[2]);
    }

    return { from: [minX, minY, minZ], to: [maxX, maxY, maxZ] };
}
