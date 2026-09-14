// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { test } from "node:test";

import { formatWarnings, type SupportWarning } from "./warnings.js";

test("formatWarnings: no warnings returns an empty array", () => {
    assert.deepEqual(formatWarnings([], false), []);
});

test("formatWarnings: a single warning is not grouped into a range", () => {
    const warnings: SupportWarning[] = [{ pos: [563, 66, -425], block: "minecraft:torch", reason: "embedded" }];
    const lines = formatWarnings(warnings, false);
    assert.equal(lines.length, 2);
    assert.match(lines[0]!, /^WARNINGS/);
    assert.match(lines[1]!, /^ {2}1x minecraft:torch at 563,66,-425: embedded/);
});

test("formatWarnings: consecutive y positions with identical block+reason group into one range line", () => {
    const warnings: SupportWarning[] = [];
    for (let y = 65; y <= 82; y++) {
        warnings.push({
            pos: [561, y, -428],
            block: "minecraft:ladder[facing=east]",
            reason: "no support behind (facing=east needs a solid block at x-1)"
        });
    }
    const lines = formatWarnings(warnings, false);
    assert.equal(lines.length, 2);
    assert.equal(
        lines[1],
        "  18x minecraft:ladder[facing=east] at x=561 y=65..82 z=-428: no support behind (facing=east needs a solid block at x-1)"
    );
});

test("formatWarnings: different reasons for the same block never merge into one group", () => {
    const warnings: SupportWarning[] = [
        { pos: [0, 64, 0], block: "minecraft:torch", reason: "nothing solid below" },
        { pos: [1, 64, 0], block: "minecraft:torch", reason: "embedded" }
    ];
    const lines = formatWarnings(warnings, false);
    assert.equal(lines.length, 3);
});

test("formatWarnings: a non-contiguous axis run (a gap) stays as separate single-position lines", () => {
    const warnings: SupportWarning[] = [
        { pos: [10, 64, 0], block: "minecraft:oak_carpet", reason: "nothing solid below" },
        { pos: [12, 64, 0], block: "minecraft:oak_carpet", reason: "nothing solid below" } // gap at x=11
    ];
    const lines = formatWarnings(warnings, false);
    assert.equal(lines.length, 3);
    assert.match(lines[1]!, /at 10,64,0:/);
    assert.match(lines[2]!, /at 12,64,0:/);
});

test("formatWarnings: truncated appends a trailing note", () => {
    const warnings: SupportWarning[] = [{ pos: [0, 64, 0], block: "minecraft:torch", reason: "nothing solid below" }];
    const lines = formatWarnings(warnings, true);
    assert.equal(lines.length, 3);
    assert.match(lines[2]!, /capped at 50/);
});

test("formatWarnings: a run along x (fixed y,z) groups on x with y/z shown as fixed values", () => {
    const warnings: SupportWarning[] = [];
    for (let x = 100; x <= 104; x++) {
        warnings.push({ pos: [x, 70, 200], block: "minecraft:oak_carpet", reason: "nothing solid below" });
    }
    const lines = formatWarnings(warnings, false);
    assert.equal(lines.length, 2);
    assert.equal(lines[1], "  5x minecraft:oak_carpet at x=100..104 y=70 z=200: nothing solid below");
});
