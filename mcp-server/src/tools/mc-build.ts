// SPDX-License-Identifier: AGPL-3.0-or-later

import type { McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";

import { PluginError } from "../errors.js";
import type { PluginClient } from "../plugin-client.js";
import { runTool } from "./helpers.js";

const fillModeEnum = z.enum(["replace", "keep", "outline", "hollow"]);

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
                'e.g. "minecraft:stone" or "minecraft:oak_log[axis=y]".'
        ),
    mode: fillModeEnum
        .optional()
        .describe(
            'Fill mode: "replace" (default) overwrites everything in the box; "keep" only fills cells that are ' +
                'currently air; "outline" places only the 1-block-thick shell; "hollow" places the shell and clears the interior to air.'
        ),
    filter: z
        .string()
        .optional()
        .describe(
            'Only replace cells whose current block matches this state string (unspecified properties act as a wildcard), ' +
                'e.g. "minecraft:air" to build only into empty space.'
        )
});

const sparseOpSchema = z.object({
    pos: z.tuple([z.number().int(), z.number().int(), z.number().int()]).describe("Absolute [x, y, z] position."),
    block: z
        .string()
        .min(1)
        .describe(
            'Block state string in the "minecraft:" namespace, with properties for orientation where relevant, ' +
                'e.g. "minecraft:oak_stairs[facing=north,half=bottom]".'
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
            )
    })
    .refine(v => (v.fills && v.fills.length > 0) || (v.blocks && v.blocks.length > 0), {
        message: '"fills" and/or "blocks" must be provided, with at least one non-empty'
    });

const DESCRIPTION = `Places blocks in the Minecraft world in bulk. Accepts a list of cuboid fill operations and/or a list of individual block placements; one call can change up to 500,000 blocks. The server executes them asynchronously across ticks, so large builds do not lag players.

WHEN TO USE: any task that places more than a handful of blocks - buildings, terrain shaping, clearing space, roads, walls. Prefer a few large \`fills\` over many small ones. Use \`blocks\` for details that need a specific orientation or state (stairs, doors, torches, signs): pass the full block state string, e.g. \`minecraft:oak_stairs[facing=north,half=bottom]\`.
WHEN NOT TO USE: to read the world (use \`mc_survey\` before building and \`mc_inspect\` after); to run a server command (use \`mc_command\`).

COORDINATES: X grows east, Z grows south, Y grows up. \`from\` and \`to\` are inclusive corners in any order. Volume = (x2-x1+1)*(y2-y1+1)*(z2-z1+1). Operations run in array order - put clearing (\`minecraft:air\`) before filling. \`mode\`: \`replace\` (default) overwrites everything; \`keep\` only fills air; \`outline\` places only the 1-block shell; \`hollow\` places the shell and clears the inside. \`filter\` restricts a fill to blocks matching that state (e.g. \`filter: "minecraft:air"\` builds only into empty space). Blocks are placed without physics updates: sand/gravel will not fall and water will not flow until something touches it.

SIDE EFFECTS: permanently modifies the world. Set \`snapshot: true\` (recommended for anything you might want to undo) to save the affected region first; the response then includes a snapshot id for \`mc_restore\`. Limits per call: 500,000 blocks total, 256 chunks footprint, one world. Requests that exceed a limit are rejected before anything changes. Typical time: 50,000 blocks in about 1-3 seconds.`;

interface FillBatchResult {
    world: string;
    ops: Array<{ index: number; volume: number; changed: number }>;
    totalVolume: number;
    totalChanged: number;
    queuedMs: number;
    elapsedMs: number;
}

interface SetBlocksResult {
    world: string;
    requested: number;
    changed: number;
    queuedMs: number;
    elapsedMs: number;
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
        async ({ world, fills, blocks, snapshot }) => {
            return runTool(client, async () => {
                const fillList = fills ?? [];
                const blockList = blocks ?? [];
                const lines: string[] = [];

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
                        ops: fillList
                    })) as FillBatchResult;
                    lines.push("Fills:");
                    for (const op of result.ops) {
                        const spec = fillList[op.index]!;
                        lines.push(
                            `  #${op.index} [${spec.from.join(",")}] -> [${spec.to.join(",")}] ${spec.block}: ${op.changed}/${op.volume} changed`
                        );
                    }
                    lines.push(`  total: ${result.totalChanged}/${result.totalVolume} changed in ${result.elapsedMs}ms`);
                }

                if (blockList.length > 0) {
                    const result = (await client.request("set_blocks", {
                        world,
                        blocks: blockList
                    })) as SetBlocksResult;
                    lines.push(`Blocks: ${result.changed}/${result.requested} changed in ${result.elapsedMs}ms`);
                }

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
