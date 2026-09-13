// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Renders a `heightmap` result (spec section 4.2 / plan section 4.3) into an
 * ASCII relief map plus a numeric matrix, a legend, and a one-line summary
 * ("largest flat zone" included). Pure functions only, no plugin/MCP
 * dependency, so this module is unit-testable in isolation.
 */

/** 8-level bucket characters, lowest to highest; the true maximum gets '@' instead of '#'. */
const BUCKET_CHARS = [".", ",", ":", "-", "=", "+", "*", "#"] as const;

export interface ReliefInput {
    from: [number, number]; // [x1, z1]
    to: [number, number]; // [x2, z2]
    /** heights[zi][xi], zi from z1..z2, xi from x1..x2 */
    heights: number[][];
    /** liquid[zi][xi]: true when the requested type and SOLID heights differ at this cell */
    liquid: boolean[][];
    /** surface material id -> count, as returned by the plugin's heightmap RPC */
    surface: Record<string, number>;
    /**
     * Whether to append the numeric height matrix. Defaults to "only when
     * the area is at most MATRIX_AUTO_CELLS cells": the matrix costs about
     * one token per cell, so a 60x60 survey would otherwise spend ~3,600
     * tokens on numbers the model rarely needs.
     */
    matrix?: boolean;
}

/** Areas up to this many cells get the numeric matrix by default (40x40). */
export const MATRIX_AUTO_CELLS = 1600;

export interface LegendEntry {
    char: string;
    label: string;
}

export interface FlatZone {
    row1: number;
    row2: number;
    col1: number;
    col2: number;
    rows: number;
    cols: number;
}

/** step = max(1, ceil(width/80), ceil(depth/60)) - plan section 4.3 rule 1. */
export function computeStep(width: number, depth: number): number {
    return Math.max(1, Math.ceil(width / 80), Math.ceil(depth / 60));
}

/**
 * Median-downsamples a heights/liquid grid by `step`. Each output cell takes
 * the median height of its step*step source cells; liquid is true if any
 * source cell in the block is liquid. A no-op (returns new arrays with the
 * same values) when `step` is 1.
 */
export function downsample(
    heights: number[][],
    liquid: boolean[][],
    step: number
): { heights: number[][]; liquid: boolean[][] } {
    const zLen = heights.length;
    const xLen = heights[0]?.length ?? 0;
    if (step <= 1) {
        return { heights: heights.map(row => row.slice()), liquid: liquid.map(row => row.slice()) };
    }
    const outRows = Math.ceil(zLen / step);
    const outCols = Math.ceil(xLen / step);
    const outHeights: number[][] = [];
    const outLiquid: boolean[][] = [];
    for (let rz = 0; rz < outRows; rz++) {
        const hRow: number[] = [];
        const lRow: boolean[] = [];
        for (let rx = 0; rx < outCols; rx++) {
            const vals: number[] = [];
            let anyLiquid = false;
            const zEnd = Math.min(zLen, (rz + 1) * step);
            const xEnd = Math.min(xLen, (rx + 1) * step);
            for (let z = rz * step; z < zEnd; z++) {
                for (let x = rx * step; x < xEnd; x++) {
                    vals.push(heights[z]![x]!);
                    if (liquid[z]![x]) anyLiquid = true;
                }
            }
            vals.sort((a, b) => a - b);
            hRow.push(vals[Math.floor(vals.length / 2)]!);
            lRow.push(anyLiquid);
        }
        outHeights.push(hRow);
        outLiquid.push(lRow);
    }
    return { heights: outHeights, liquid: outLiquid };
}

/** One cell's relief character: '~' for liquid, '=' for a flat (min==max) area, else an 8-level bucket, '@' at the true max. */
export function reliefChar(height: number, min: number, max: number, isLiquid: boolean): string {
    if (isLiquid) return "~";
    if (max === min) return "=";
    if (height >= max) return "@";
    const frac = (height - min) / (max - min);
    const bucket = Math.min(7, Math.max(0, Math.floor(frac * 8)));
    return BUCKET_CHARS[bucket]!;
}

/** Builds the y-range legend for the 8 buckets plus '@' (max) and, if any liquid cell exists, '~'. */
export function buildLegend(min: number, max: number, hasLiquid: boolean): LegendEntry[] {
    const entries: LegendEntry[] = [];
    if (min === max) {
        entries.push({ char: "=", label: `${min}` });
    } else {
        const range = max - min;
        for (let b = 0; b < 8; b++) {
            const lo = min + Math.floor((b * range) / 8);
            let hi = min + Math.ceil(((b + 1) * range) / 8) - 1;
            if (b === 7) hi = Math.max(lo, max - 1);
            if (hi < lo) hi = lo;
            entries.push({ char: BUCKET_CHARS[b]!, label: lo === hi ? `${lo}` : `${lo}-${hi}` });
        }
        entries.push({ char: "@", label: `${max}` });
    }
    if (hasLiquid) {
        entries.push({ char: "~", label: "liquid surface" });
    }
    return entries;
}

/**
 * Largest axis-aligned rectangle of cells within `tolerance` blocks of
 * `median` (plan section 4.3 rule 6), via the standard "largest rectangle in
 * a binary matrix" histogram/stack algorithm, O(rows*cols).
 */
export function largestFlatZone(heights: number[][], median: number, tolerance = 1): FlatZone | null {
    const rows = heights.length;
    if (rows === 0) return null;
    const cols = heights[0]?.length ?? 0;
    if (cols === 0) return null;

    const flat: boolean[][] = heights.map(row => row.map(h => Math.abs(h - median) <= tolerance));
    const hist = new Array<number>(cols).fill(0);
    let best: { area: number; row1: number; row2: number; col1: number; col2: number } | null = null;

    for (let r = 0; r < rows; r++) {
        for (let c = 0; c < cols; c++) {
            hist[c] = flat[r]![c] ? hist[c]! + 1 : 0;
        }
        const stack: number[] = [];
        for (let c = 0; c <= cols; c++) {
            const h = c === cols ? 0 : hist[c]!;
            while (stack.length > 0 && hist[stack[stack.length - 1]!]! >= h) {
                const top = stack.pop()!;
                const height = hist[top]!;
                const left = stack.length > 0 ? stack[stack.length - 1]! + 1 : 0;
                const width = c - left;
                const area = height * width;
                if (height > 0 && (!best || area > best.area)) {
                    best = { area, row1: r - height + 1, row2: r, col1: left, col2: c - 1 };
                }
            }
            stack.push(c);
        }
    }
    if (!best) return null;
    return {
        row1: best.row1,
        row2: best.row2,
        col1: best.col1,
        col2: best.col2,
        rows: best.row2 - best.row1 + 1,
        cols: best.col2 - best.col1 + 1
    };
}

function median(values: number[]): number {
    const sorted = values.slice().sort((a, b) => a - b);
    return sorted[Math.floor(sorted.length / 2)]!;
}

/** Exported for reuse by mc_survey's image-format path and mc_render's `view:"heightmap"` text (heightmap-view.ts). */
export function shortBlockName(id: string): string {
    return id.startsWith("minecraft:") ? id.slice("minecraft:".length) : id;
}

function renderMapLines(charGrid: string[][], x1: number, z1: number, step: number): string[] {
    const cols = charGrid[0]?.length ?? 0;
    const gutter = 7; // 6-wide z label + 1 space
    const tickEvery = 5;
    const ruler = new Array<string>(gutter + cols).fill(" ");
    for (let c = 0; c < cols; c += tickEvery) {
        const worldX = x1 + c * step;
        const s = String(worldX);
        for (let i = 0; i < s.length && gutter + c + i < ruler.length; i++) {
            ruler[gutter + c + i] = s[i]!;
        }
    }
    const lines: string[] = [ruler.join("")];
    for (let r = 0; r < charGrid.length; r++) {
        const worldZ = z1 + r * step;
        const label = String(worldZ).padStart(6, " ");
        lines.push(`${label} ${charGrid[r]!.join("")}`);
    }
    return lines;
}

/** Exported for reuse by mc_survey's image-format path (`matrix: true` appends this after the image's text block). */
export function renderMatrix(heights: number[][], x1: number, z1: number, step: number): string[] {
    const header =
        step > 1
            ? `Height matrix (downsampled: 1 cell = ${step}x${step} blocks, median height; z rows top-to-bottom, x columns left-to-right, top-left = x=${x1} z=${z1}):`
            : `Height matrix (z rows top-to-bottom, x columns left-to-right, top-left = x=${x1} z=${z1}):`;
    const lines: string[] = [header];
    for (let zi = 0; zi < heights.length; zi++) {
        lines.push(`z=${z1 + zi * step}: ${heights[zi]!.join(" ")}`);
    }
    return lines;
}

/** Renders the full text block: summary line, ASCII map, legend, numeric matrix. Pure ASCII. */
export function renderRelief(input: ReliefInput): string {
    const [x1, z1] = input.from;
    const [x2, z2] = input.to;
    const width = x2 - x1 + 1;
    const depth = z2 - z1 + 1;
    const step = computeStep(width, depth);

    const { heights, liquid } = downsample(input.heights, input.liquid, step);

    let min = Infinity;
    let max = -Infinity;
    const flatVals: number[] = [];
    for (const row of input.heights) {
        for (const h of row) {
            flatVals.push(h);
            if (h < min) min = h;
            if (h > max) max = h;
        }
    }
    const med = median(flatVals);
    const hasLiquid = input.liquid.some(row => row.some(Boolean));

    const charGrid = heights.map((row, zi) => row.map((h, xi) => reliefChar(h, min, max, liquid[zi]![xi]!)));
    const mapLines = renderMapLines(charGrid, x1, z1, step);

    const legend = buildLegend(min, max, hasLiquid);
    const legendLine = legend.map(e => `${e.char} ${e.label}`).join("   ");

    const zone = largestFlatZone(heights, med, 1);
    let zoneLine = "Largest flat zone: none found.";
    if (zone) {
        const zx1 = x1 + zone.col1 * step;
        const zx2 = Math.min(x2, x1 + (zone.col2 + 1) * step - 1);
        const zz1 = z1 + zone.row1 * step;
        const zz2 = Math.min(z2, z1 + (zone.row2 + 1) * step - 1);
        const zoneWidth = zx2 - zx1 + 1;
        const zoneDepth = zz2 - zz1 + 1;
        zoneLine = `Largest flat zone (+/-1 block): ${zoneWidth}x${zoneDepth} at x=${zx1}..${zx2} z=${zz1}..${zz2}, y=${med}.`;
    }

    const surfaceEntries = Object.entries(input.surface).sort((a, b) => b[1] - a[1]);
    const totalSurface = surfaceEntries.reduce((s, [, c]) => s + c, 0) || 1;
    const surfaceText =
        surfaceEntries.length > 0
            ? surfaceEntries
                  .slice(0, 4)
                  .map(([name, count]) => `${shortBlockName(name)} ${Math.round((count / totalSurface) * 100)}%`)
                  .join(", ")
            : "unknown";

    const summary = `Area x=[${x1}..${x2}] z=[${z1}..${z2}] (${width}x${depth}). Surface y: min ${min}, max ${max}, median ${med}. Surface: ${surfaceText}. ${zoneLine}`;

    const downsampleNote =
        step > 1 ? [`Downsampled: 1 char = ${step}x${step} blocks (median height per cell).`, ""] : [];

    const includeMatrix = input.matrix ?? width * depth <= MATRIX_AUTO_CELLS;
    const matrixLines = includeMatrix
        ? renderMatrix(heights, x1, z1, step)
        : [`Height matrix omitted for this ${width}x${depth} area (${width * depth} cells); pass matrix: true if you need exact per-block heights, or survey a smaller area.`];

    return [summary, "", ...downsampleNote, ...mapLines, "", `Legend: ${legendLine}`, "", ...matrixLines].join("\n");
}
