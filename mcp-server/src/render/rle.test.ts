// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { test } from "node:test";

import { decodeRegionData, type RegionDataJson } from "./rle.js";

/** A hand-built 3x2x2 (x by y by z... here dx=3,dy=2,dz=2) region: 12 cells, y outer/z middle/x inner order. */
function handBuiltRegion(): RegionDataJson {
    // Cell order (y,z,x): (0,0,0..2) (0,1,0..2) (1,0,0..2) (1,1,0..2)
    // Sequence of palette indices per cell:
    //   y=0,z=0: 0 0 1
    //   y=0,z=1: 1 1 1
    //   y=1,z=0: 2 0 0
    //   y=1,z=1: 0 0 0
    const sequence = [0, 0, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0];
    const runs: Array<[number, number]> = [];
    for (const idx of sequence) {
        const last = runs[runs.length - 1];
        if (last && last[0] === idx) {
            last[1] += 1;
        } else {
            runs.push([idx, 1]);
        }
    }
    return {
        bounds: { from: [10, 64, 20], to: [12, 65, 21] }, // dx=3, dy=2, dz=2
        order: "y,z,x",
        palette: ["minecraft:air", "minecraft:stone", "minecraft:dirt"],
        runs,
        volume: 12
    };
}

test("decodeRegionData: round trip against a hand-built palette/runs matches the original sequence", () => {
    const data = handBuiltRegion();
    const decoded = decodeRegionData(data);

    assert.equal(decoded.volume, 12);
    assert.deepEqual(decoded.size, { dx: 3, dy: 2, dz: 2 });

    const expected = [
        "minecraft:air",
        "minecraft:air",
        "minecraft:stone",
        "minecraft:stone",
        "minecraft:stone",
        "minecraft:stone",
        "minecraft:dirt",
        "minecraft:air",
        "minecraft:air",
        "minecraft:air",
        "minecraft:air",
        "minecraft:air"
    ];
    assert.deepEqual([...decoded], expected);
});

test("decodeRegionData: at() performs correct random access by absolute world coordinates", () => {
    const decoded = decodeRegionData(handBuiltRegion());
    // y=64,z=20 row: x=10,11,12 -> air, air, stone
    assert.equal(decoded.at(10, 64, 20), "minecraft:air");
    assert.equal(decoded.at(11, 64, 20), "minecraft:air");
    assert.equal(decoded.at(12, 64, 20), "minecraft:stone");
    // y=65,z=20 row: x=10,11,12 -> dirt, air, air
    assert.equal(decoded.at(10, 65, 20), "minecraft:dirt");
    assert.equal(decoded.at(11, 65, 20), "minecraft:air");
    assert.equal(decoded.at(12, 65, 20), "minecraft:air");
});

test("decodeRegionData: at() throws outside the decoded bounds", () => {
    const decoded = decodeRegionData(handBuiltRegion());
    assert.throws(() => decoded.at(999, 64, 20));
    assert.throws(() => decoded.at(10, 999, 20));
});

test("decodeRegionData: an all-uniform region collapses to a single run and decodes correctly", () => {
    const data: RegionDataJson = {
        bounds: { from: [0, 0, 0], to: [1, 1, 1] }, // 2x2x2 = 8 cells
        order: "y,z,x",
        palette: ["minecraft:air"],
        runs: [[0, 8]],
        volume: 8
    };
    const decoded = decodeRegionData(data);
    assert.equal(decoded.volume, 8);
    assert.deepEqual([...decoded], new Array(8).fill("minecraft:air"));
    assert.equal(decoded.at(1, 1, 1), "minecraft:air");
});

test("decodeRegionData: throws when runs do not add up to the region volume", () => {
    const data: RegionDataJson = {
        bounds: { from: [0, 0, 0], to: [1, 1, 1] }, // 8 cells
        order: "y,z,x",
        palette: ["minecraft:air"],
        runs: [[0, 5]], // too short
        volume: 8
    };
    assert.throws(() => decodeRegionData(data), /decoded 5 cells but region volume is 8/);
});
