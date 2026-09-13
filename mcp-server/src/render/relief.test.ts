// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { test } from "node:test";

import { buildLegend, computeStep, downsample, largestFlatZone, reliefChar, renderRelief } from "./relief.js";

test("reliefChar: liquid always renders as ~", () => {
    assert.equal(reliefChar(70, 60, 80, true), "~");
    assert.equal(reliefChar(60, 60, 80, true), "~");
    assert.equal(reliefChar(80, 60, 80, true), "~");
});

test("reliefChar: flat area (min == max) renders as =", () => {
    assert.equal(reliefChar(64, 64, 64, false), "=");
});

test("reliefChar: minimum bucket is '.', maximum is '@'", () => {
    assert.equal(reliefChar(60, 60, 68, false), ".");
    assert.equal(reliefChar(68, 60, 68, false), "@");
});

test("reliefChar: buckets increase monotonically with height", () => {
    const chars = ["#", "*", "+", "=", "-", ":", ",", "."];
    const order = [".", ",", ":", "-", "=", "+", "*", "#"];
    const min = 0;
    const max = 80;
    let lastRank = -1;
    for (let h = min; h < max; h++) {
        const c = reliefChar(h, min, max, false);
        const rank = order.indexOf(c);
        assert.ok(rank >= 0, `unexpected char '${c}'`);
        assert.ok(rank >= lastRank, `bucket rank went backwards at h=${h}`);
        lastRank = rank;
    }
    void chars;
});

test("computeStep: no downsampling under the thresholds", () => {
    assert.equal(computeStep(50, 50), 1);
    assert.equal(computeStep(80, 60), 1);
});

test("computeStep: downsamples wide/deep areas", () => {
    assert.equal(computeStep(160, 60), 2);
    assert.equal(computeStep(80, 120), 2);
    assert.equal(computeStep(240, 240), 4);
});

test("downsample: takes the median height and any-liquid per step block", () => {
    // 4x4 grid, step=2 -> 2x2 output
    const heights = [
        [10, 10, 20, 20],
        [10, 12, 20, 22],
        [30, 30, 40, 40],
        [30, 32, 40, 42]
    ];
    const liquid = [
        [false, false, false, false],
        [false, false, false, true],
        [false, false, false, false],
        [false, false, false, false]
    ];
    const { heights: outH, liquid: outL } = downsample(heights, liquid, 2);
    assert.deepEqual(outH, [
        [10, 20],
        [30, 40]
    ]);
    assert.deepEqual(outL, [
        [false, true],
        [false, false]
    ]);
});

test("downsample: step=1 is a no-op copy", () => {
    const heights = [
        [1, 2],
        [3, 4]
    ];
    const liquid = [
        [false, true],
        [false, false]
    ];
    const { heights: outH, liquid: outL } = downsample(heights, liquid, 1);
    assert.deepEqual(outH, heights);
    assert.deepEqual(outL, liquid);
    assert.notEqual(outH, heights); // must be a copy, not the same array reference
});

test("buildLegend: flat region collapses to a single '=' entry", () => {
    const legend = buildLegend(64, 64, false);
    assert.deepEqual(legend, [{ char: "=", label: "64" }]);
});

test("buildLegend: 8 buckets plus '@' for the max, '~' only when liquid is present", () => {
    const withoutLiquid = buildLegend(60, 68, false);
    assert.equal(withoutLiquid.length, 9); // 8 buckets + '@'
    assert.equal(withoutLiquid[0]!.char, ".");
    assert.equal(withoutLiquid[7]!.char, "#");
    assert.equal(withoutLiquid[8]!.char, "@");
    assert.equal(withoutLiquid[8]!.label, "68");
    assert.ok(!withoutLiquid.some(e => e.char === "~"));

    const withLiquid = buildLegend(60, 68, true);
    assert.equal(withLiquid.length, 10);
    assert.equal(withLiquid[9]!.char, "~");
    assert.equal(withLiquid[9]!.label, "liquid surface");
});

test("largestFlatZone: finds an embedded flat rectangle in a synthetic 50x50 map", () => {
    const size = 50;
    const heights: number[][] = [];
    for (let z = 0; z < size; z++) {
        const row: number[] = [];
        for (let x = 0; x < size; x++) {
            row.push(x % 2 === 0 ? 40 : 90); // wildly uneven background
        }
        heights.push(row);
    }
    // Embed an 18x14 flat plateau at value 66: rows (z) 5..18, cols (x) 10..27.
    const rowStart = 5;
    const rowEnd = 18; // inclusive, 14 rows
    const colStart = 10;
    const colEnd = 27; // inclusive, 18 cols
    for (let z = rowStart; z <= rowEnd; z++) {
        for (let x = colStart; x <= colEnd; x++) {
            heights[z]![x] = 66;
        }
    }

    const zone = largestFlatZone(heights, 66, 1);
    assert.ok(zone, "expected a flat zone to be found");
    assert.equal(zone!.row1, rowStart);
    assert.equal(zone!.row2, rowEnd);
    assert.equal(zone!.col1, colStart);
    assert.equal(zone!.col2, colEnd);
    assert.equal(zone!.rows, rowEnd - rowStart + 1);
    assert.equal(zone!.cols, colEnd - colStart + 1);
});

test("largestFlatZone: returns null for an empty grid", () => {
    assert.equal(largestFlatZone([], 0, 1), null);
});

test("renderRelief: produces pure ASCII output containing the summary, legend, and flat-zone line", () => {
    const size = 20;
    const heights: number[][] = [];
    const liquid: boolean[][] = [];
    for (let z = 0; z < size; z++) {
        const hRow: number[] = [];
        const lRow: boolean[] = [];
        for (let x = 0; x < size; x++) {
            hRow.push(64 + ((x + z) % 5));
            lRow.push(false);
        }
        heights.push(hRow);
        liquid.push(lRow);
    }
    const text = renderRelief({
        from: [100, 200],
        to: [119, 219],
        heights,
        liquid,
        surface: { "minecraft:grass_block": 300, "minecraft:stone": 100 }
    });
    assert.match(text, /^Area x=\[100\.\.119\] z=\[200\.\.219\]/);
    assert.match(text, /Largest flat zone/);
    assert.match(text, /Legend:/);
    assert.doesNotMatch(text, /[^\x00-\x7F]/, "output must be pure ASCII");
});
