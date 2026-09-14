// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { test } from "node:test";

import {
    buildLegend,
    CLASS_GROUND,
    CLASS_LIQUID,
    CLASS_VEGETATION,
    computeStep,
    downsample,
    largestFlatZone,
    reliefChar,
    renderRelief
} from "./relief.js";

test("reliefChar: liquid always renders as ~", () => {
    assert.equal(reliefChar(70, 60, 80, CLASS_LIQUID), "~");
    assert.equal(reliefChar(60, 60, 80, CLASS_LIQUID), "~");
    assert.equal(reliefChar(80, 60, 80, CLASS_LIQUID), "~");
});

test("reliefChar: vegetation always renders as T, regardless of height", () => {
    assert.equal(reliefChar(70, 60, 80, CLASS_VEGETATION), "T");
    assert.equal(reliefChar(60, 60, 80, CLASS_VEGETATION), "T");
    assert.equal(reliefChar(80, 60, 80, CLASS_VEGETATION), "T");
});

test("reliefChar: flat area (min == max) renders as =", () => {
    assert.equal(reliefChar(64, 64, 64, CLASS_GROUND), "=");
});

test("reliefChar: minimum bucket is '.', maximum is '@'", () => {
    assert.equal(reliefChar(60, 60, 68, CLASS_GROUND), ".");
    assert.equal(reliefChar(68, 60, 68, CLASS_GROUND), "@");
});

test("reliefChar: buckets increase monotonically with height", () => {
    const chars = ["#", "*", "+", "=", "-", ":", ",", "."];
    const order = [".", ",", ":", "-", "=", "+", "*", "#"];
    const min = 0;
    const max = 80;
    let lastRank = -1;
    for (let h = min; h < max; h++) {
        const c = reliefChar(h, min, max, CLASS_GROUND);
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

test("downsample: takes the median height (excluding vegetation) and any-liquid/any-vegetation per step block", () => {
    // 4x4 grid, step=2 -> 2x2 output
    const heights = [
        [10, 10, 20, 20],
        [10, 12, 20, 22],
        [30, 30, 40, 40],
        [30, 32, 40, 42]
    ];
    const classes = [
        [CLASS_GROUND, CLASS_GROUND, CLASS_GROUND, CLASS_GROUND],
        [CLASS_GROUND, CLASS_GROUND, CLASS_GROUND, CLASS_LIQUID],
        [CLASS_GROUND, CLASS_GROUND, CLASS_GROUND, CLASS_GROUND],
        [CLASS_GROUND, CLASS_GROUND, CLASS_GROUND, CLASS_GROUND]
    ];
    const { heights: outH, classes: outC } = downsample(heights, classes, 2);
    assert.deepEqual(outH, [
        [10, 20],
        [30, 40]
    ]);
    assert.deepEqual(outC, [
        [CLASS_GROUND, CLASS_LIQUID],
        [CLASS_GROUND, CLASS_GROUND]
    ]);
});

test("downsample: a vegetation cell's height is excluded from its block's median", () => {
    // 2x2 block: three ground cells at 60, one vegetation "trunk top" at 90 - median must ignore the 90.
    const heights = [
        [60, 60],
        [60, 90]
    ];
    const classes = [
        [CLASS_GROUND, CLASS_GROUND],
        [CLASS_GROUND, CLASS_VEGETATION]
    ];
    const { heights: outH, classes: outC } = downsample(heights, classes, 2);
    assert.deepEqual(outH, [[60]]);
    assert.deepEqual(outC, [[CLASS_VEGETATION]], "block class is vegetation since at least one sub-cell is");
});

test("downsample: an all-vegetation block falls back to the raw median instead of an empty set", () => {
    const heights = [
        [10, 20],
        [30, 40]
    ];
    const classes = [
        [CLASS_VEGETATION, CLASS_VEGETATION],
        [CLASS_VEGETATION, CLASS_VEGETATION]
    ];
    const { heights: outH, classes: outC } = downsample(heights, classes, 2);
    assert.equal(outH[0]![0], 30); // median of the raw [10,20,30,40] set (index floor(4/2)=2), same tie-break rule as elsewhere
    assert.deepEqual(outC, [[CLASS_VEGETATION]]);
});

test("downsample: step=1 is a no-op copy", () => {
    const heights = [
        [1, 2],
        [3, 4]
    ];
    const classes = [
        [CLASS_GROUND, CLASS_LIQUID],
        [CLASS_GROUND, CLASS_GROUND]
    ];
    const { heights: outH, classes: outC } = downsample(heights, classes, 1);
    assert.deepEqual(outH, heights);
    assert.deepEqual(outC, classes);
    assert.notEqual(outH, heights); // must be a copy, not the same array reference
});

test("buildLegend: flat region collapses to a single '=' entry", () => {
    const legend = buildLegend(64, 64, false, false);
    assert.deepEqual(legend, [{ char: "=", label: "64" }]);
});

test("buildLegend: 8 buckets plus '@' for the max, '~' only when liquid is present, 'T' only when vegetation is present", () => {
    const bare = buildLegend(60, 68, false, false);
    assert.equal(bare.length, 9); // 8 buckets + '@'
    assert.equal(bare[0]!.char, ".");
    assert.equal(bare[7]!.char, "#");
    assert.equal(bare[8]!.char, "@");
    assert.equal(bare[8]!.label, "68");
    assert.ok(!bare.some(e => e.char === "~"));
    assert.ok(!bare.some(e => e.char === "T"));

    const withLiquid = buildLegend(60, 68, true, false);
    assert.equal(withLiquid.length, 10);
    assert.equal(withLiquid[9]!.char, "~");
    assert.equal(withLiquid[9]!.label, "liquid surface");

    const withBoth = buildLegend(60, 68, true, true);
    assert.equal(withBoth.length, 11);
    assert.equal(withBoth[9]!.char, "~");
    assert.equal(withBoth[10]!.char, "T");
    assert.equal(withBoth[10]!.label, "trees / vegetation");
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

test("largestFlatZone: a vegetation cell inside an otherwise-flat rectangle is never included", () => {
    const size = 10;
    const heights: number[][] = Array.from({ length: size }, () => new Array(size).fill(64));
    const classes: number[][] = Array.from({ length: size }, () => new Array(size).fill(CLASS_GROUND));
    // A single "tree" in the middle of an otherwise perfectly flat 10x10 area must split the flat zone.
    classes[5]![5] = CLASS_VEGETATION;

    const zone = largestFlatZone(heights, 64, 1, classes);
    assert.ok(zone, "expected a flat zone to be found");
    const areaCoversVegetationCell = zone!.row1 <= 5 && 5 <= zone!.row2 && zone!.col1 <= 5 && 5 <= zone!.col2;
    assert.ok(!areaCoversVegetationCell, "the vegetation cell must not be inside the reported flat zone");
});

test("largestFlatZone: returns null for an empty grid", () => {
    assert.equal(largestFlatZone([], 0, 1), null);
});

test("renderRelief: produces pure ASCII output containing the summary, legend, and flat-zone line", () => {
    const size = 20;
    const heights: number[][] = [];
    const classes: number[][] = [];
    for (let z = 0; z < size; z++) {
        const hRow: number[] = [];
        const cRow: number[] = [];
        for (let x = 0; x < size; x++) {
            hRow.push(64 + ((x + z) % 5));
            cRow.push(CLASS_GROUND);
        }
        heights.push(hRow);
        classes.push(cRow);
    }
    const text = renderRelief({
        from: [100, 200],
        to: [119, 219],
        heights,
        classes,
        surface: { "minecraft:grass_block": 300, "minecraft:stone": 100 }
    });
    assert.match(text, /^Area x=\[100\.\.119\] z=\[200\.\.219\]/);
    assert.match(text, /Largest flat zone/);
    assert.match(text, /Legend:/);
    assert.doesNotMatch(text, /[^\x00-\x7F]/, "output must be pure ASCII");
});

test("renderRelief: a forest no longer paints as water (~) and instead shows T, with correct min/max excluding trunk tops", () => {
    // 10x10 all-grass at height 64, except a 2x2 "tree": trunk top at height 68 (vegetation), classified
    // vegetation, not liquid. Regression test for Bug 1 (docs/prompts/step4g-prompt.md).
    const size = 10;
    const heights: number[][] = Array.from({ length: size }, () => new Array(size).fill(64));
    const classes: number[][] = Array.from({ length: size }, () => new Array(size).fill(CLASS_GROUND));
    heights[4]![4] = 68;
    heights[4]![5] = 68;
    classes[4]![4] = CLASS_VEGETATION;
    classes[4]![5] = CLASS_VEGETATION;

    const text = renderRelief({
        from: [0, 0],
        to: [size - 1, size - 1],
        heights,
        classes,
        surface: { "minecraft:grass_block": 96, "minecraft:oak_log": 4 }
    });

    assert.doesNotMatch(text, /~/, "no liquid cells exist, so no ~ should appear anywhere");
    assert.match(text, /T/, "the tree canopy must render as T");
    assert.match(text, /min 64, max 64/, "min/max must exclude the trunk-top height of 68");
});
