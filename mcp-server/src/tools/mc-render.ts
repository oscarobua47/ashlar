// SPDX-License-Identifier: AGPL-3.0-or-later

import type { McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";

import type { PluginClient } from "../plugin-client.js";
import { runToolContent } from "./helpers.js";

const MAX_VOLUME = 200_000;

const VIEWS = ["top", "north", "south", "east", "west", "slice"] as const;

const inputSchema = z.object({
    world: z.string().min(1).optional().describe("World name. Omitted uses the plugin's configured default world."),
    from: z.tuple([z.number().int(), z.number().int(), z.number().int()]).describe("Inclusive [x, y, z] corner of the region to render."),
    to: z
        .tuple([z.number().int(), z.number().int(), z.number().int()])
        .describe("Inclusive [x, y, z] corner opposite `from`. Order does not matter."),
    view: z
        .enum(VIEWS)
        .optional()
        .describe(
            '"top" (default): top-down layout, shaded like a vanilla in-game map. "north"/"south"/"east"/"west": ' +
                'the facade seen from that compass direction, shaded by depth. "slice": an exact-color 2D cross-section ' +
                '(requires the `slice` parameter).'
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
        .describe("Draw a coordinate grid line every N blocks, with coordinate labels along the top/left edges. Default 10; 0 disables the grid entirely.")
});

const DESCRIPTION = `Renders a region as a PNG image from a chosen viewpoint, so an AI can literally see terrain layout and building results instead of only reading block-state text. Calls the plugin's "render" RPC, which reads the region (the same read as mc_inspect) and paints one of six views: a top-down layout with vanilla-map-style shading, a facade from one of the four compass sides with distance shading, or an exact-color 2D cross-section slice at a fixed x/y/z.

WHEN TO USE: "top" to check overall terrain shape, a build's footprint, or how a structure sits relative to the land, before or after building. "north"/"south"/"east"/"west" to inspect a facade - window/door placement, wall symmetry, roof lines - as seen from that compass direction. "slice" with an axis and coordinate for a floor plan (axis "y") or a vertical cut through a wall or room (axis "x"/"z"), e.g. to confirm a room is actually hollow.

WHEN NOT TO USE: to get exact block-state strings, orientations, or counts - use mc_inspect, which returns real data, not a colored approximation. Map colors are lossy: different blocks (different wood planks, terracotta colors) can render near-identically, so check the legend and fall back to mc_inspect when the exact block matters. To cheaply find ground height over a large area, use mc_survey instead.

PARAMETERS: \`from\`/\`to\` are inclusive [x,y,z] corners in any order, volume <= 200,000 blocks (same cap as mc_inspect). \`view\` (default "top") is one of top/north/south/east/west/slice. \`slice\` is required when \`view\` is "slice": \`{axis: "x"|"y"|"z", at: <coordinate>}\`, \`at\` must lie within the from/to box on that axis. \`scale\` is pixels per block, 0-16 (default 0 = auto, longer side about 1024px). \`grid\` is grid spacing in blocks, 0-64 (default 10; 0 disables grid lines/labels).

SIDE EFFECTS: none - read-only, like mc_inspect. Returns an image block plus a text block: view/axes/top-left coordinate/grid interval, and a legend of the top rendered blocks as hex color, name, and pixel count. A large region can take a second or two; the plugin caps the PNG at 3MB and halves the scale (down to 1) automatically if needed.`;

interface RenderResult {
    world: string;
    view: string;
    bounds: { from: [number, number, number]; to: [number, number, number] };
    width: number;
    height: number;
    scale: number;
    axes: { right: string; down: string };
    topLeft: [number, number];
    grid: number;
    legend: Array<{ block: string; color: string; pixels: number }>;
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
        async ({ world, from, to, view, slice, scale, grid }) => {
            return runToolContent(client, "mc_render", async () => {
                const [x1, y1, z1] = [Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2])];
                const [x2, y2, z2] = [Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2])];
                const volume = (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
                if (volume > MAX_VOLUME) {
                    throw new Error(
                        `mc_render region volume ${volume} exceeds the 200,000-block limit. Reduce the from/to range or split it into several calls.`
                    );
                }
                if ((view ?? "top") === "slice" && !slice) {
                    throw new Error('mc_render view "slice" requires the "slice" parameter: {"axis": "x"|"y"|"z", "at": <coordinate>}.');
                }

                const result = (await client.request("render", {
                    world,
                    from: [x1, y1, z1],
                    to: [x2, y2, z2],
                    view,
                    slice,
                    scale,
                    grid
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
                lines.push("Legend (Minecraft map color -> block; similar blocks can share a color, disambiguate here):");
                for (const entry of result.legend) {
                    lines.push(`  ${entry.color} ${entry.block} (${entry.pixels} px)`);
                }

                return [
                    { type: "image", data: result.png, mimeType: "image/png" },
                    { type: "text", text: lines.join("\n") }
                ];
            });
        }
    );
}
