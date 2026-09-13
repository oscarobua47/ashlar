// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Renders a decoded `read_region` result (see rle.ts) into either a block
 * statistics table (mc_inspect with no `slice`) or an ASCII cross-section
 * grid with a legend (mc_inspect with `slice`). Pure functions, no
 * plugin/MCP dependency, so this module is unit-testable in isolation.
 */

import { computeStep } from "./relief.js";
import type { DecodedRegion } from "./rle.js";

export const AIR_ID = "minecraft:air";

export interface SliceSpec {
    axis: "x" | "y" | "z";
    at: number;
}

/** Special characters tried first, most-frequent block first (air is always '.', never in this list). */
const SPECIAL_CHARS = ["#", "=", "+", "*", "%", "@", "&", "o", "x", "~", "^", "-", ":"];
/** Lowercase letters not already used above, tried next. */
const FALLBACK_LETTERS = "abcdefghijklmnpqrstuvwyz".split("");
const FALLBACK_DIGITS = "0123456789".split("");

/** The character assigned to the block at this rank (0 = most frequent). Never returns '.' (reserved for air). */
export function sliceChar(rank: number): string {
    if (rank < SPECIAL_CHARS.length) return SPECIAL_CHARS[rank]!;
    const afterSpecial = rank - SPECIAL_CHARS.length;
    if (afterSpecial < FALLBACK_LETTERS.length) return FALLBACK_LETTERS[afterSpecial]!;
    const afterLetters = afterSpecial - FALLBACK_LETTERS.length;
    if (afterLetters < FALLBACK_DIGITS.length) return FALLBACK_DIGITS[afterLetters]!;
    return "?";
}

/** Assigns one character to each block id, in the given (most-frequent-first) order. `blockId` list must exclude air. */
export function assignSliceChars(blocksMostFrequentFirst: string[]): Map<string, string> {
    const map = new Map<string, string>();
    blocksMostFrequentFirst.forEach((block, i) => map.set(block, sliceChar(i)));
    return map;
}

function range(lo: number, hi: number): number[] {
    const out: number[] = [];
    for (let v = lo; v <= hi; v++) out.push(v);
    return out;
}

function rangeDesc(lo: number, hi: number): number[] {
    const out: number[] = [];
    for (let v = hi; v >= lo; v--) out.push(v);
    return out;
}

/**
 * Downsamples a categorical grid by `step`, taking the most frequent block
 * (mode) of each step*step block as the representative cell - median makes
 * no sense for non-numeric data, unlike relief.ts's height downsampling.
 */
export function downsampleSlice(
    grid: string[][],
    rowsCoords: number[],
    colsCoords: number[],
    step: number
): { grid: string[][]; rowsCoords: number[]; colsCoords: number[] } {
    if (step <= 1) {
        return { grid: grid.map(row => row.slice()), rowsCoords: rowsCoords.slice(), colsCoords: colsCoords.slice() };
    }
    const outRows = Math.ceil(rowsCoords.length / step);
    const outCols = Math.ceil(colsCoords.length / step);
    const outGrid: string[][] = [];
    const outRowsCoords: number[] = [];
    const outColsCoords: number[] = [];
    for (let c = 0; c < outCols; c++) outColsCoords.push(colsCoords[c * step]!);
    for (let r = 0; r < outRows; r++) {
        outRowsCoords.push(rowsCoords[r * step]!);
        const row: string[] = [];
        const rEnd = Math.min(rowsCoords.length, (r + 1) * step);
        for (let c = 0; c < outCols; c++) {
            const cEnd = Math.min(colsCoords.length, (c + 1) * step);
            const counts = new Map<string, number>();
            for (let rr = r * step; rr < rEnd; rr++) {
                for (let cc = c * step; cc < cEnd; cc++) {
                    const b = grid[rr]![cc]!;
                    counts.set(b, (counts.get(b) ?? 0) + 1);
                }
            }
            let bestBlock = "";
            let bestCount = -1;
            for (const [b, count] of counts) {
                if (count > bestCount) {
                    bestCount = count;
                    bestBlock = b;
                }
            }
            row.push(bestBlock);
        }
        outGrid.push(row);
    }
    return { grid: outGrid, rowsCoords: outRowsCoords, colsCoords: outColsCoords };
}

function renderGridLines(charGrid: string[][], rowsCoords: number[], colsCoords: number[]): string[] {
    const gutter = 7; // 6-wide row label + 1 space
    const tickEvery = 5;
    const ruler = new Array<string>(gutter + colsCoords.length).fill(" ");
    for (let c = 0; c < colsCoords.length; c += tickEvery) {
        const s = String(colsCoords[c]);
        for (let i = 0; i < s.length && gutter + c + i < ruler.length; i++) {
            ruler[gutter + c + i] = s[i]!;
        }
    }
    const lines: string[] = [ruler.join("")];
    for (let r = 0; r < charGrid.length; r++) {
        const label = String(rowsCoords[r]).padStart(6, " ");
        lines.push(`${label} ${charGrid[r]!.join("")}`);
    }
    return lines;
}

/**
 * Renders a single-layer cross-section as an ASCII grid with a legend.
 * `axis:'y'` gives a top-down slice (rows = z, cols = x); `axis:'x'`/`'z'`
 * give a vertical elevation slice (rows = y, high-to-low, cols = the other
 * horizontal axis). Downsamples with the same step rule as relief.ts when
 * wider than 80 columns or taller than 60 rows.
 */
export function renderSlice(decoded: DecodedRegion, spec: SliceSpec): string {
    const [minX, minY, minZ] = decoded.bounds.from;
    const [maxX, maxY, maxZ] = decoded.bounds.to;

    let rowsCoords: number[];
    let colsCoords: number[];
    let get: (rowV: number, colV: number) => string;
    let rowAxisLabel: string;
    let colAxisLabel: string;

    if (spec.axis === "y") {
        if (spec.at < minY || spec.at > maxY) {
            throw new Error(`slice: y=${spec.at} is outside the read region's y range [${minY},${maxY}]`);
        }
        rowsCoords = range(minZ, maxZ);
        colsCoords = range(minX, maxX);
        get = (z, x) => decoded.at(x, spec.at, z);
        rowAxisLabel = "z";
        colAxisLabel = "x";
    } else if (spec.axis === "x") {
        if (spec.at < minX || spec.at > maxX) {
            throw new Error(`slice: x=${spec.at} is outside the read region's x range [${minX},${maxX}]`);
        }
        rowsCoords = rangeDesc(minY, maxY);
        colsCoords = range(minZ, maxZ);
        get = (y, z) => decoded.at(spec.at, y, z);
        rowAxisLabel = "y";
        colAxisLabel = "z";
    } else {
        if (spec.at < minZ || spec.at > maxZ) {
            throw new Error(`slice: z=${spec.at} is outside the read region's z range [${minZ},${maxZ}]`);
        }
        rowsCoords = rangeDesc(minY, maxY);
        colsCoords = range(minX, maxX);
        get = (y, x) => decoded.at(x, y, spec.at);
        rowAxisLabel = "y";
        colAxisLabel = "x";
    }

    const grid: string[][] = rowsCoords.map(r => colsCoords.map(c => get(r, c)));

    const freq = new Map<string, number>();
    let hasAir = false;
    for (const row of grid) {
        for (const b of row) {
            if (b === AIR_ID) {
                hasAir = true;
                continue;
            }
            freq.set(b, (freq.get(b) ?? 0) + 1);
        }
    }
    const distinctSorted = [...freq.entries()].sort((a, b) => b[1] - a[1]).map(([b]) => b);
    const charMap = assignSliceChars(distinctSorted);

    const step = computeStep(colsCoords.length, rowsCoords.length);
    const { grid: outGrid, rowsCoords: outRowsCoords, colsCoords: outColsCoords } =
        step > 1 ? downsampleSlice(grid, rowsCoords, colsCoords, step) : { grid, rowsCoords, colsCoords };

    const charGrid = outGrid.map(row => row.map(b => (b === AIR_ID ? "." : (charMap.get(b) ?? "?"))));
    const gridLines = renderGridLines(charGrid, outRowsCoords, outColsCoords);

    const header =
        `Slice axis=${spec.axis} at=${spec.at} (rows=${rowAxisLabel} ${rowsCoords.length}, cols=${colAxisLabel} ${colsCoords.length})` +
        (step > 1 ? `, downsampled 1 char = ${step}x${step} cells (most frequent block)` : "") +
        ".";

    const legendLines = ["Legend:"];
    if (hasAir) legendLines.push(`  . ${AIR_ID}`);
    for (const block of distinctSorted) {
        legendLines.push(`  ${charMap.get(block)} ${block}`);
    }

    return [header, "", ...gridLines, "", ...legendLines].join("\n");
}

/** Block-frequency table + bounding box, for mc_inspect with no `slice`. */
export function renderStats(decoded: DecodedRegion, world: string): string {
    const counts = new Map<string, number>();
    for (const idx of decoded.indices) {
        const b = decoded.palette[idx]!;
        counts.set(b, (counts.get(b) ?? 0) + 1);
    }
    const total = decoded.volume || 1;
    const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1]);

    const [fx, fy, fz] = decoded.bounds.from;
    const [tx, ty, tz] = decoded.bounds.to;
    const lines: string[] = [];
    lines.push(
        `Region ${world} x=[${fx}..${tx}] y=[${fy}..${ty}] z=[${fz}..${tz}] ` +
            `(${decoded.size.dx}x${decoded.size.dy}x${decoded.size.dz} = ${decoded.volume} blocks).`
    );
    lines.push("");
    lines.push(`${sorted.length} distinct block state(s):`);
    for (const [block, count] of sorted) {
        const pct = ((count / total) * 100).toFixed(1);
        lines.push(`  ${String(count).padStart(8, " ")}  ${pct.padStart(5, " ")}%  ${block}`);
    }
    return lines.join("\n");
}
