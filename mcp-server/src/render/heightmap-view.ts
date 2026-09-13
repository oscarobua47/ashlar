// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Formatting shared by mc_survey's default image path and mc_render's
 * `view:"heightmap"` (docs/prompts/step4f-prompt.md), both of which call the
 * plugin's `render` RPC with `view:"heightmap"` and get back the same JSON
 * shape: a one-line summary (area, min/max/median height, dominant surface
 * materials, largest flat zone) and a hypsometric-band legend, matching the
 * text mc_survey's ASCII (`format:"text"`) path produces from the same
 * underlying numbers.
 */

import { shortBlockName } from "./relief.js";

export interface HeightmapFlatZone {
    x1: number;
    z1: number;
    x2: number;
    z2: number;
    y: number;
    width: number;
    depth: number;
}

export interface HeightmapLegendBand {
    color: string;
    label: string;
}

export interface HeightmapRenderFields {
    bounds: { from: [number, number]; to: [number, number] };
    heights: { min: number; max: number; median: number };
    surface: Record<string, number>;
    flatZone: HeightmapFlatZone | null;
    legend: HeightmapLegendBand[];
    liquidCells: number;
    contour: number;
}

/** The one-line summary: same shape as `renderRelief`'s summary line in relief.ts, built from the server-aggregated numbers instead of a raw grid. */
export function heightmapSummaryLine(result: HeightmapRenderFields): string {
    const [x1, z1] = result.bounds.from;
    const [x2, z2] = result.bounds.to;
    const width = x2 - x1 + 1;
    const depth = z2 - z1 + 1;

    const surfaceEntries = Object.entries(result.surface).sort((a, b) => b[1] - a[1]);
    const totalSurface = surfaceEntries.reduce((s, [, c]) => s + c, 0) || 1;
    const surfaceText =
        surfaceEntries.length > 0
            ? surfaceEntries
                  .slice(0, 4)
                  .map(([name, count]) => `${shortBlockName(name)} ${Math.round((count / totalSurface) * 100)}%`)
                  .join(", ")
            : "unknown";

    const zone = result.flatZone;
    const zoneLine = zone
        ? `Largest flat zone (+/-1 block): ${zone.width}x${zone.depth} at x=${zone.x1}..${zone.x2} z=${zone.z1}..${zone.z2}, y=${zone.y}.`
        : "Largest flat zone: none found.";

    const liquidText = result.liquidCells > 0 ? ` Liquid cells: ${result.liquidCells}.` : "";

    return (
        `Area x=[${x1}..${x2}] z=[${z1}..${z2}] (${width}x${depth}). Surface y: min ${result.heights.min}, ` +
        `max ${result.heights.max}, median ${result.heights.median}. Surface: ${surfaceText}. ${zoneLine}${liquidText}`
    );
}

/** The hypsometric-band legend line, e.g. "Legend: #3f76e4 water   #2d6a1f y 60-61   ...". */
export function heightmapLegendLine(legend: HeightmapLegendBand[]): string {
    return `Legend: ${legend.map(b => `${b.color} ${b.label}`).join("   ")}`;
}

export function heightmapContourLine(contour: number): string {
    return contour > 0
        ? `Contour lines: every ${contour} blocks of height (a darker line where the height crosses a multiple of ${contour}).`
        : "Contour lines: disabled.";
}
