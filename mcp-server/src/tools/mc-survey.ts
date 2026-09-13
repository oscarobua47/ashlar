// SPDX-License-Identifier: AGPL-3.0-or-later

import type { McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";

import type { PluginClient } from "../plugin-client.js";
import { renderRelief } from "../render/relief.js";
import { runTool } from "./helpers.js";

const MAX_AREA = 200_000;

const HEIGHTMAP_TYPES = ["SOLID", "SOLID_OR_LIQUID", "SOLID_OR_LIQUID_NO_LEAVES", "ANY"] as const;

interface HeightmapResult {
    world: string;
    from: [number, number];
    to: [number, number];
    type: string;
    order: string;
    heights: number[][]; // [zi][xi]
    min: number;
    max: number;
    surface: Record<string, number>;
}

const inputSchema = z.object({
    world: z.string().min(1).optional().describe("World name. Omitted uses the plugin's configured default world."),
    from: z
        .tuple([z.number().int(), z.number().int()])
        .describe("Inclusive [x, z] corner of the area to survey (no y - the tool measures the surface height)."),
    to: z
        .tuple([z.number().int(), z.number().int()])
        .describe("Inclusive [x, z] corner opposite `from`. Order does not matter; the tool normalizes min/max."),
    type: z
        .enum(HEIGHTMAP_TYPES)
        .optional()
        .describe(
            "Which surface to measure. SOLID: highest solid block, ignoring water (a river reads as its bed). " +
                "SOLID_OR_LIQUID: highest solid or liquid block (a river reads as its water surface). " +
                "SOLID_OR_LIQUID_NO_LEAVES (default): same as SOLID_OR_LIQUID but ignores leaves, useful for finding " +
                "ground level under a forest canopy. ANY: highest non-air block including leaves and snow layers."
        ),
    matrix: z
        .boolean()
        .optional()
        .describe(
            "Whether to include the numeric height matrix (one number per block, about one token each). " +
                "Default: included only for areas up to 40x40 (1600 cells). Set true when you need exact heights for a larger area."
        )
});

const DESCRIPTION = `Surveys terrain by reading surface height over a rectangular x/z area and rendering it as an ASCII relief map so an AI can form spatial intuition about the ground before building. Internally calls the plugin's "heightmap" RPC twice in parallel (once with the requested \`type\`, once with SOLID) and compares the results to mark cells covered by water or lava, since one heightmap call alone cannot distinguish "flat ground" from "flat water". The response includes a one-line summary (area, min/max/median height, dominant surface materials, and the largest flat buildable zone found), an ASCII relief map with a coordinate ruler and height legend, and, for areas up to 40x40, the numeric height matrix for precise calculations (larger areas omit it unless \`matrix: true\`, since it costs about one token per block). Areas wider than 80 blocks or deeper than 60 are downsampled (each character then represents a step x step block, noted in the output) so the map always fits on screen.

WHEN TO USE: before any nontrivial build, to find a flat spot, see where water/lava is, and pick a sensible y level, rather than guessing coordinates. Also useful mid-project to check terrain outside the current build area, or to answer "what does the land around x,z look like".

WHEN NOT TO USE: for a full 3D read of placed blocks or underground structure (use mc_inspect, which reads actual block data, not just the surface). Do not call it repeatedly over tiny sub-areas when one call over the whole area would do; the area limit exists so one call can cover a meaningful build site.

COORDINATES: X grows east, Z grows south. \`from\`/\`to\` are inclusive [x, z] pairs (no y component - this tool measures height, it does not take one). Area = (x2-x1+1)*(z2-z1+1) and must be at most 200,000 cells; a single call comfortably covers e.g. a 300x300 area. Y values in the output are absolute world height.

SIDE EFFECTS: none - this is a read-only tool and never modifies the world. A large area (tens of thousands of cells) may take a second or two on the plugin side; there is no other cost to calling it freely.`;

export function registerMcSurvey(server: McpServer, client: PluginClient): void {
    server.registerTool(
        "mc_survey",
        {
            title: "Survey terrain",
            description: DESCRIPTION,
            inputSchema,
            annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
        },
        async ({ world, from, to, type, matrix }) => {
            return runTool(client, "mc_survey", async () => {
                const [x1raw, z1raw] = from;
                const [x2raw, z2raw] = to;
                const x1 = Math.min(x1raw, x2raw);
                const x2 = Math.max(x1raw, x2raw);
                const z1 = Math.min(z1raw, z2raw);
                const z2 = Math.max(z1raw, z2raw);
                const area = (x2 - x1 + 1) * (z2 - z1 + 1);
                if (area > MAX_AREA) {
                    throw new Error(
                        `mc_survey area ${area} exceeds the 200,000-cell limit. Reduce the from/to range or split it into several calls.`
                    );
                }

                const requestedType = type ?? "SOLID_OR_LIQUID_NO_LEAVES";
                const params = { world, from: [x1, z1], to: [x2, z2] };

                const [requested, solid] = (await Promise.all([
                    client.request("heightmap", { ...params, type: requestedType }),
                    client.request("heightmap", { ...params, type: "SOLID" })
                ])) as [HeightmapResult, HeightmapResult];

                const liquid: boolean[][] = requested.heights.map((row, zi) =>
                    row.map((h, xi) => h !== solid.heights[zi]![xi]!)
                );

                return renderRelief({
                    from: [x1, z1],
                    to: [x2, z2],
                    heights: requested.heights,
                    liquid,
                    surface: requested.surface,
                    matrix
                });
            });
        }
    );
}
