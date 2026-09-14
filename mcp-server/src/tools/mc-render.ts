// SPDX-License-Identifier: AGPL-3.0-or-later

import type { McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";

import type { PluginClient } from "../plugin-client.js";
import { heightmapContourLine, heightmapLegendLine, heightmapSummaryLine, type HeightmapLegendBand } from "../render/heightmap-view.js";
import { runToolContent } from "./helpers.js";

const MAX_VOLUME = 200_000;
const MAX_HEIGHTMAP_AREA = 200_000;

const VIEWS = ["top", "north", "south", "east", "west", "slice", "heightmap"] as const;
const HEIGHTMAP_TYPES = ["SOLID", "SOLID_OR_LIQUID", "SOLID_OR_LIQUID_NO_LEAVES", "ANY"] as const;

const inputSchema = z.object({
    world: z.string().min(1).optional().describe("World name. Omitted uses the plugin's configured default world."),
    from: z
        .tuple([z.number().int(), z.number().int(), z.number().int()])
        .describe("Inclusive [x, y, z] corner of the region to render. For view \"heightmap\" (an x/z-only view), the y is accepted but ignored."),
    to: z
        .tuple([z.number().int(), z.number().int(), z.number().int()])
        .describe("Inclusive [x, y, z] corner opposite `from`. Order does not matter."),
    view: z
        .enum(VIEWS)
        .optional()
        .describe(
            '"top" (default): top-down layout, shaded like a vanilla in-game map. "north"/"south"/"east"/"west": ' +
                'the facade seen from that compass direction, shaded by depth. "slice": an exact-color 2D cross-section ' +
                '(requires the `slice` parameter). "heightmap": a color-banded terrain height map with contour lines - ' +
                "an x/z area only (y from from/to is ignored), area-priced like mc_survey rather than volume-priced."
        ),
    slice: z
        .object({
            axis: z.enum(["x", "y", "z"]).describe('Which axis is held fixed. "y" gives a top-down floor plan at one exact layer; "x" or "z" gives a vertical cross-section.'),
            at: z.number().int().describe("The fixed coordinate value on that axis. Must lie within [from, to] on that axis.")
        })
        .optional()
        .describe('Required when `view` is "slice"; ignored otherwise.'),
    scale: z
        .number()
        .int()
        .min(0)
        .max(16)
        .optional()
        .describe("Pixels per block, 0-16. Default 0 = auto-sized so the image's longer side is about 1024px (capped at 4,000,000 total pixels)."),
    grid: z
        .number()
        .int()
        .min(0)
        .max(64)
        .optional()
        .describe("Draw a coordinate grid line every N blocks, with coordinate labels along the top/left edges. Default 10; 0 disables the grid entirely."),
    heightmapType: z
        .enum(HEIGHTMAP_TYPES)
        .optional()
        .describe(
            'Only used when `view` is "heightmap": which surface to measure (same meaning as mc_survey\'s `type`). ' +
                "Default SOLID_OR_LIQUID_NO_LEAVES."
        ),
    contour: z
        .number()
        .int()
        .min(0)
        .max(4096)
        .optional()
        .describe('Only used when `view` is "heightmap": draw a contour line every N blocks of height, 0 disables. Default 5.')
});

const DESCRIPTION = `Renders a region as a PNG image from a chosen viewpoint, so an AI can see terrain layout and building results instead of reading block-state text. Paints one of seven views: a top-down layout with vanilla-map-style shading, a facade from a compass side with distance shading, an exact-color 2D cross-section slice, or a color-banded terrain height map ("heightmap", area-priced) with contour lines - the same view mc_survey's default image uses, exposed here for the raw render call.

WHEN TO USE: "top" to check overall terrain shape, a build's footprint, or how a structure sits relative to the land, before or after building. "north"/"south"/"east"/"west" to inspect a facade - window/door placement, wall symmetry, roof lines. "slice" with an axis and coordinate for a floor plan (axis "y") or a vertical cut through a wall or room, e.g. to confirm a room is hollow. "heightmap" for a terrain relief picture over a large area - prefer mc_survey in most cases, since it also gives the exact numbers as text.

WHEN NOT TO USE: to get exact block-state strings, orientations, or counts - use mc_inspect, which returns real data, not a colored approximation. Map colors are lossy: different blocks can render near-identically, so check the legend. To survey ground height with the numbers included, use mc_survey instead.

PARAMETERS: \`from\`/\`to\` are inclusive [x,y,z] corners in any order. "top"/"heightmap" are area-priced (x/z footprint <= 200,000 cells, any y range); side views/"slice" are volume-priced (<= 200,000 blocks) - for a facade, limit y to the building's height. \`view\` (default "top") is one of top/north/south/east/west/slice/heightmap; \`slice\` is required for \`view: "slice"\`. \`scale\` is pixels per block, 0-16 (default 0 = auto). \`grid\` is grid spacing, 0-64 (default 10; 0 disables it). \`heightmapType\`/\`contour\` only apply to \`view: "heightmap"\`.

SIDE EFFECTS: none - read-only. Returns an image block plus a text block: view/axes/top-left coordinate/grid interval, and a legend (top rendered blocks as hex color, name, pixel count; for "heightmap", height bands plus a summary line with min/max/median height, surface materials, and the largest flat zone). The plugin caps the PNG at 3MB and halves the scale automatically if needed.`;

interface RenderResult {
    world: string;
    view: string;
    bounds: { from: [number, number, number] | [number, number]; to: [number, number, number] | [number, number] };
    width: number;
    height: number;
    scale: number;
    axes: { right: string; down: string };
    topLeft: [number, number];
    grid: number;
    contour?: number;
    legend: Array<{ block: string; color: string; pixels: number } | HeightmapLegendBand>;
    heights?: { min: number; max: number; median: number };
    surface?: Record<string, number>;
    flatZone?: { x1: number; z1: number; x2: number; z2: number; y: number; width: number; depth: number } | null;
    liquidCells?: number;
    treeCells?: number;
    png: string;
    bytes: number;
}

export function registerMcRender(server: McpServer, client: PluginClient): void {
    server.registerTool(
        "mc_render",
        {
            title: "Render view",
            description: DESCRIPTION,
            inputSchema,
            annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
        },
        async ({ world, from, to, view, slice, scale, grid, heightmapType, contour }) => {
            return runToolContent(client, "mc_render", async () => {
                const resolvedView = view ?? "top";
                const [x1, y1, z1] = [Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2])];
                const [x2, y2, z2] = [Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2])];

                if (resolvedView === "heightmap" || resolvedView === "top") {
                    // Bug 2 (docs/prompts/step4g-prompt.md): "top" reads one block per column, so it is
                    // area-priced (x/z footprint) like "heightmap", not volume-priced like every other view -
                    // the y range can be anything within world bounds.
                    const area = (x2 - x1 + 1) * (z2 - z1 + 1);
                    if (area > MAX_HEIGHTMAP_AREA) {
                        throw new Error(
                            `mc_render ${resolvedView} area ${area} exceeds the 200,000-cell limit. Reduce the from/to range or split it into several calls.`
                        );
                    }
                } else {
                    const volume = (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
                    if (volume > MAX_VOLUME) {
                        throw new Error(
                            `mc_render region volume ${volume} exceeds the 200,000-block limit. Reduce the from/to range or split it into several calls.`
                        );
                    }
                }
                if (resolvedView === "slice" && !slice) {
                    throw new Error('mc_render view "slice" requires the "slice" parameter: {"axis": "x"|"y"|"z", "at": <coordinate>}.');
                }

                const result = (await client.request("render", {
                    world,
                    from: resolvedView === "heightmap" ? [x1, z1] : [x1, y1, z1],
                    to: resolvedView === "heightmap" ? [x2, z2] : [x2, y2, z2],
                    view,
                    slice,
                    scale,
                    grid,
                    type: resolvedView === "heightmap" ? heightmapType : undefined,
                    contour: resolvedView === "heightmap" ? contour : undefined
                })) as RenderResult;

                const lines: string[] = [];
                lines.push(
                    `View: ${result.view} | Bounds: [${result.bounds.from.join(",")}] -> [${result.bounds.to.join(",")}] | ` +
                        `Size: ${result.width}x${result.height}px @ scale ${result.scale} (${result.scale}px/block)`
                );
                lines.push(
                    `Axes: right=${result.axes.right}, down=${result.axes.down}; the pixel at (0,0) is world coordinate ` +
                        `(${result.topLeft[0]}, ${result.topLeft[1]}) along those two axes.`
                );
                lines.push(
                    result.grid > 0
                        ? `Grid: a line every ${result.grid} blocks (thicker every ${result.grid * 5}), with coordinate labels along the top/left edges.`
                        : "Grid: disabled."
                );

                if (result.view === "heightmap" && result.heights && result.surface && result.flatZone !== undefined && result.liquidCells !== undefined) {
                    lines.push(heightmapContourLine(result.contour ?? 0));
                    lines.push(
                        heightmapSummaryLine({
                            bounds: result.bounds as { from: [number, number]; to: [number, number] },
                            heights: result.heights,
                            surface: result.surface,
                            flatZone: result.flatZone,
                            legend: result.legend as HeightmapLegendBand[],
                            liquidCells: result.liquidCells,
                            treeCells: result.treeCells ?? 0,
                            contour: result.contour ?? 0
                        })
                    );
                    lines.push(heightmapLegendLine(result.legend as HeightmapLegendBand[]));
                } else {
                    lines.push("Legend (Minecraft map color -> block; similar blocks can share a color, disambiguate here):");
                    for (const entry of result.legend as Array<{ block: string; color: string; pixels: number }>) {
                        lines.push(`  ${entry.color} ${entry.block} (${entry.pixels} px)`);
                    }
                }

                return [
                    { type: "image", data: result.png, mimeType: "image/png" },
                    { type: "text", text: lines.join("\n") }
                ];
            });
        }
    );
}
