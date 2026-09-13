// SPDX-License-Identifier: AGPL-3.0-or-later

import type { McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";

import { decodeRegionData, type RegionDataJson, type SignEntry } from "../render/rle.js";
import { renderSlice, renderStats } from "../render/slice.js";
import type { PluginClient } from "../plugin-client.js";
import { runTool } from "./helpers.js";

const MAX_VOLUME = 200_000;

const inputSchema = z.object({
    world: z.string().min(1).optional().describe("World name. Omitted uses the plugin's configured default world."),
    from: z.tuple([z.number().int(), z.number().int(), z.number().int()]).describe("Inclusive [x, y, z] corner to read."),
    to: z
        .tuple([z.number().int(), z.number().int(), z.number().int()])
        .describe("Inclusive [x, y, z] corner opposite `from`. Order does not matter."),
    slice: z
        .object({
            axis: z.enum(["x", "y", "z"]).describe('Which axis is held fixed. "y" gives a top-down layer; "x" or "z" gives a vertical elevation cross-section.'),
            at: z.number().int().describe("The fixed coordinate value on that axis, must lie within [from, to] on that axis.")
        })
        .optional()
        .describe(
            "When given, render a single 2D cross-section as an ASCII grid with a legend instead of the full block-count table."
        )
});

const DESCRIPTION = `Reads actual block data back from the world and summarizes it, for verifying what mc_build actually produced (or for inspecting terrain/structures that already exist). Calls the plugin's "read_region" RPC, which returns a palette + run-length-encoded region (Minecraft repeats blocks heavily, so this compresses far better than a raw 3D array), then decodes it locally.

Two output modes: with no \`slice\`, returns a block-frequency table (every distinct block state in the region, sorted by count, with percentages) plus the bounding box - good for "did the tower actually get built out of stone, and is there any leftover scaffolding block left inside". With \`slice\`, returns one 2D cross-section rendered as an ASCII grid: one character per distinct block, a legend mapping characters to block states, and a coordinate ruler - good for "show me a horizontal floor plan at y=70" or "show me a vertical cut through this wall". A hollow structure's cross-section will visibly show as a ring or shell of one character around an interior of \`.\` (air). Grids wider than 80 columns or taller than 60 rows are downsampled (each character then represents a block of cells, using the most common block in that block), noted in the output. For a picture of a layer instead, use mc_render's \`view: "slice"\`; this tool's ASCII slice stays the way to get exact block states.

WHEN TO USE: after mc_build, to confirm changes landed correctly, especially with snapshot:true builds where you may want to compare before/after; to check what a snapshot region currently contains before deciding to restore it; to read pre-existing structures or terrain in detail (mc_survey only reads surface height, not full 3D block data).

WHEN NOT TO USE: to find ground level over a large area (use mc_survey, which is far cheaper for that). To place or modify blocks (use mc_build).

COORDINATES: X grows east, Z grows south, Y grows up. \`from\`/\`to\` are inclusive corners in any order. Volume = (x2-x1+1)*(y2-y1+1)*(z2-z1+1), capped at 200,000 cells per call.

SIDE EFFECTS: none - this is a read-only tool and never modifies the world.`;

interface ReadRegionResult extends RegionDataJson {
    world?: string;
}

export function registerMcInspect(server: McpServer, client: PluginClient): void {
    server.registerTool(
        "mc_inspect",
        {
            title: "Inspect blocks",
            description: DESCRIPTION,
            inputSchema,
            annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
        },
        async ({ world, from, to, slice }) => {
            return runTool(client, "mc_inspect", async () => {
                const [x1, y1, z1] = [Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2])];
                const [x2, y2, z2] = [Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2])];
                const volume = (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
                if (volume > MAX_VOLUME) {
                    throw new Error(
                        `mc_inspect region volume ${volume} exceeds the 200,000-block limit. Reduce the from/to range or split it into several calls.`
                    );
                }

                const result = (await client.request("read_region", {
                    world,
                    from: [x1, y1, z1],
                    to: [x2, y2, z2]
                })) as ReadRegionResult;
                const decoded = decodeRegionData(result);

                const body = slice ? renderSlice(decoded, slice) : renderStats(decoded, result.world ?? world ?? "(default)");
                const signsSection = renderSigns(result.signs, result.signsTruncated);
                return signsSection ? `${body}\n\n${signsSection}` : body;
            });
        }
    );
}

/**
 * Renders the {@code Signs:} section (Fix 3, docs/prompts/step4d-prompt.md):
 * one {@code x,y,z: "line1 | line2 | line3 | line4"} line per sign, using
 * the front side's text. Returns {@code null} when there are no signs in
 * the region, so mc_inspect output for a region with no signs is unchanged.
 */
function renderSigns(signs: SignEntry[] | undefined, truncated: boolean | undefined): string | null {
    if (!signs || signs.length === 0) {
        return null;
    }
    const lines = ["Signs:"];
    for (const sign of signs) {
        const [x, y, z] = sign.pos;
        const hasFrontText = sign.front.some(line => line.length > 0);
        const text = (hasFrontText ? sign.front : sign.back).join(" | ");
        lines.push(`  ${x},${y},${z}: "${text}"`);
    }
    if (truncated) {
        lines.push("  (more signs exist in this region than could be listed; narrow the from/to range to see them)");
    }
    return lines.join("\n");
}
