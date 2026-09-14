// SPDX-License-Identifier: AGPL-3.0-or-later

import type { McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";

import type { PluginClient } from "../plugin-client.js";
import { renderMatrix, renderRelief } from "../render/relief.js";
import { heightmapContourLine, heightmapLegendLine, heightmapSummaryLine, type HeightmapLegendBand } from "../render/heightmap-view.js";
import { runToolContent, type ContentBlock } from "./helpers.js";

const MAX_AREA = 200_000;

const HEIGHTMAP_TYPES = ["SOLID", "SOLID_OR_LIQUID", "SOLID_OR_LIQUID_NO_LEAVES", "ANY"] as const;

interface HeightmapResult {
    world: string;
    from: [number, number];
    to: [number, number];
    type: string;
    order: string;
    heights: number[][]; // [zi][xi]
    classes: number[][]; // [zi][xi]: 0 ground, 1 liquid, 2 vegetation
    min: number;
    max: number;
    surface: Record<string, number>;
}

interface HeightmapRenderResult {
    world: string;
    view: "heightmap";
    bounds: { from: [number, number]; to: [number, number] };
    width: number;
    height: number;
    scale: number;
    axes: { right: string; down: string };
    topLeft: [number, number];
    grid: number;
    contour: number;
    legend: HeightmapLegendBand[];
    heights: { min: number; max: number; median: number };
    surface: Record<string, number>;
    flatZone: { x1: number; z1: number; x2: number; z2: number; y: number; width: number; depth: number } | null;
    liquidCells: number;
    treeCells: number;
    png: string;
    bytes: number;
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
    format: z
        .enum(["image", "text"])
        .optional()
        .describe(
            'Output format. Leave unset. "image" (default): a color heightmap picture plus the exact numbers (min/max/median ' +
                'height, surface materials, largest flat zone) as text. "text": an ASCII relief map instead of the picture - ' +
                "only when the user explicitly asks for a text map; you can see images, so do not pick this on your own."
        ),
    matrix: z
        .boolean()
        .optional()
        .describe(
            'Whether to include the numeric height matrix (one number per block, about one token each), appended after ' +
                'the image/map. In format:"text" it is included by default for areas up to 40x40 (1600 cells) and omitted ' +
                'above that unless set true. In the default format:"image" it is always omitted unless set true (the image ' +
                "already conveys relief; set true when you need exact per-block numbers for a larger area)."
        )
});

const DESCRIPTION = `Surveys terrain by reading surface height over a rectangular x/z area, so an AI can form spatial intuition about the ground before building. By default (\`format: "image"\`) it renders a color heightmap: 8 hypsometric bands from dark green (low) through yellow/tan/brown to light gray (high), blue for water/lava, relief shading, a coordinate grid, and contour lines - plus text with the numbers a picture cannot give: area, min/max/median height, dominant surface materials, and the largest flat buildable zone with its coordinates and y. Always use the default image format; \`format: "text"\` (an ASCII relief map) exists only for clients that truly cannot show images or when the user explicitly asks for a text map - it is larger and harder to read than the picture. Either format accepts \`matrix: true\` to append the numeric per-block height matrix.

WHEN TO USE: before any nontrivial build, to find a flat spot, see where water/lava is, and pick a sensible y level, rather than guessing coordinates. Also useful mid-project to check terrain outside the current build area, or to answer "what does the land around x,z look like".

WHEN NOT TO USE: for a full 3D read of placed blocks or underground structure (use mc_inspect). Do not call it repeatedly over tiny sub-areas when one call over the whole area would do; the area limit exists so one call can cover a meaningful build site.

COORDINATES: X grows east, Z grows south. \`from\`/\`to\` are inclusive [x, z] pairs (no y - this tool measures height, it does not take one). Area = (x2-x1+1)*(z2-z1+1), at most 200,000 cells; one call comfortably covers e.g. a 300x300 area. The image is area-priced, not volume-priced, so a wide, shallow survey costs the same as a square one of equal area. Y values in the output are absolute world height.

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
        async ({ world, from, to, type, format, matrix }) => {
            return runToolContent(client, "mc_survey", async () => {
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

                if ((format ?? "image") === "text") {
                    // Bug 1 (docs/prompts/step4g-prompt.md): liquid/vegetation now come from the plugin's own
                    // per-cell classification (`classes`), so a single heightmap call suffices - no more second
                    // SOLID call to detect liquid by height difference (which also misfired under tree canopies).
                    const requested = (await client.request("heightmap", {
                        world,
                        from: [x1, z1],
                        to: [x2, z2],
                        type: requestedType
                    })) as HeightmapResult;

                    const text = renderRelief({
                        from: [x1, z1],
                        to: [x2, z2],
                        heights: requested.heights,
                        classes: requested.classes,
                        surface: requested.surface,
                        matrix
                    });
                    return [{ type: "text", text }];
                }

                const result = (await client.request("render", {
                    world,
                    from: [x1, z1],
                    to: [x2, z2],
                    view: "heightmap",
                    type: requestedType
                })) as HeightmapRenderResult;

                const lines: string[] = [
                    heightmapSummaryLine(result),
                    "",
                    heightmapLegendLine(result.legend),
                    "",
                    `Axes: right=${result.axes.right}, down=${result.axes.down}; the pixel at (0,0) is world coordinate ` +
                        `(${result.topLeft[0]}, ${result.topLeft[1]}) along those two axes.`,
                    result.grid > 0
                        ? `Grid: a line every ${result.grid} blocks (thicker every ${result.grid * 5}), with coordinate labels along the top/left edges.`
                        : "Grid: disabled.",
                    heightmapContourLine(result.contour)
                ];

                const content: ContentBlock[] = [
                    { type: "image", data: result.png, mimeType: "image/png" },
                    { type: "text", text: lines.join("\n") }
                ];

                if (matrix) {
                    const hm = (await client.request("heightmap", {
                        world,
                        from: [x1, z1],
                        to: [x2, z2],
                        type: requestedType
                    })) as HeightmapResult;
                    content.push({ type: "text", text: renderMatrix(hm.heights, x1, z1, 1).join("\n") });
                }

                return content;
            });
        }
    );
}
