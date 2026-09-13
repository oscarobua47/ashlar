// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { test } from "node:test";

import { decodeRegionData, type RegionDataJson } from "./rle.js";
import { AIR_ID, assignSliceChars, renderSlice, renderStats, sliceChar } from "./slice.js";

test("sliceChar: most-frequent blocks get the special-character set first, in order", () => {
    assert.equal(sliceChar(0), "#");
    assert.equal(sliceChar(1), "=");
    assert.equal(sliceChar(12), ":"); // last of the 13 special chars
    assert.equal(sliceChar(13), "a"); // falls back to letters (o, x are skipped since already special)
});

test("assignSliceChars: legend order matches most-frequent-first input order", () => {
    const map = assignSliceChars(["minecraft:stone", "minecraft:dirt", "minecraft:oak_log"]);
    assert.equal(map.get("minecraft:stone"), "#");
    assert.equal(map.get("minecraft:dirt"), "=");
    assert.equal(map.get("minecraft:oak_log"), "+");
    assert.equal(map.size, 3);
});

function toRuns(sequence: number[]): Array<[number, number]> {
    const runs: Array<[number, number]> = [];
    for (const idx of sequence) {
        const last = runs[runs.length - 1];
        if (last && last[0] === idx) {
            last[1] += 1;
        } else {
            runs.push([idx, 1]);
        }
    }
    return runs;
}

/** A single y=10 layer, 5x5 in x/z, whose border is stone and whose interior is air (a hollow square). */
function hollowLayerRegion(): RegionDataJson {
    const sequence: number[] = [];
    for (let z = 0; z <= 4; z++) {
        for (let x = 0; x <= 4; x++) {
            const isBorder = z === 0 || z === 4 || x === 0 || x === 4;
            sequence.push(isBorder ? 1 : 0);
        }
    }
    return {
        bounds: { from: [0, 10, 0], to: [4, 10, 4] },
        order: "y,z,x",
        palette: [AIR_ID, "minecraft:stone"],
        runs: toRuns(sequence),
        volume: 25
    };
}

test("renderSlice: a hollow box's mid-layer slice renders as a ring", () => {
    const decoded = decodeRegionData(hollowLayerRegion());
    const text = renderSlice(decoded, { axis: "y", at: 10 });
    const lines = text.split("\n");

    // Layout: [header, "", ruler, row(z=0)..row(z=4), "", ...legend]
    const rows = lines.slice(3, 8).map(line => line.slice(7)); // strip the 7-wide gutter
    assert.deepEqual(rows, ["#####", "#...#", "#...#", "#...#", "#####"]);

    assert.match(text, /Legend:/);
    assert.match(text, new RegExp(`\\. ${AIR_ID.replace(":", "\\:")}`));
    assert.match(text, /# minecraft:stone/);
    assert.doesNotMatch(text, /[^\x00-\x7F]/, "output must be pure ASCII");
});

test("renderStats: reports block counts sorted descending with percentages summing near 100", () => {
    const decoded = decodeRegionData(hollowLayerRegion());
    const text = renderStats(decoded, "world");
    assert.match(text, /2 distinct block state\(s\)/);
    // stone (16 border cells) must be listed before air (9 interior cells)
    const stoneIdx = text.indexOf("minecraft:stone");
    const airIdx = text.indexOf(AIR_ID);
    assert.ok(stoneIdx >= 0 && airIdx >= 0 && stoneIdx < airIdx);
    assert.match(text, /16.*64\.0%.*minecraft:stone/);
});
