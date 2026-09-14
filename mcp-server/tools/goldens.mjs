#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Generates cross-language golden files (docs/private/prompts/step7b-prompt.md): feeds a fixed set
// of synthetic inputs through the TypeScript text-formatting functions (render/relief.ts,
// render/slice.ts, render/rle.ts, render/heightmap-view.ts, tools/warnings.ts, errors.ts) and writes
// `{input, expected}` JSON files that the Java port's GoldenTest asserts against byte-for-byte. Zero
// dependencies; run after `npm run build`:
//
//   cd mcp-server && npm run build && node tools/goldens.mjs

import { mkdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

import { buildLegend, renderRelief } from "../dist/render/relief.js";
import { decodeRegionData } from "../dist/render/rle.js";
import { renderSlice, renderStats } from "../dist/render/slice.js";
import { heightmapContourLine, heightmapLegendLine, heightmapSummaryLine } from "../dist/render/heightmap-view.js";
import { formatWarnings } from "../dist/tools/warnings.js";
import { formatPluginError, PluginError } from "../dist/errors.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.join(__dirname, "..", "..", "plugin", "src", "test", "resources", "goldens");
mkdirSync(OUT_DIR, { recursive: true });

let written = 0;
function save(name, input, expected) {
    const file = path.join(OUT_DIR, `${name}.json`);
    writeFileSync(file, JSON.stringify({ input, expected }, null, 2) + "\n", "utf8");
    written++;
    console.log(`wrote ${name}.json`);
}

// ---------------------------------------------------------------------
// relief.ts: a 40x40 area with a hill, a lake, and a forest patch
// ---------------------------------------------------------------------

function buildTerrain40x40() {
    const size = 40;
    const heights = [];
    const classes = [];
    for (let z = 0; z < size; z++) {
        const hRow = [];
        const cRow = [];
        for (let x = 0; x < size; x++) {
            const inLake = x >= 2 && x <= 10 && z >= 28 && z <= 36;
            const inForest = x >= 25 && x <= 33 && z >= 3 && z <= 11;
            if (inLake) {
                hRow.push(55);
                cRow.push(1); // CLASS_LIQUID
            } else if (inForest) {
                hRow.push(71); // trunk-top height
                cRow.push(2); // CLASS_VEGETATION
            } else {
                const dist = Math.sqrt((x - 20) ** 2 + (z - 20) ** 2);
                const hill = Math.round(8 * Math.max(0, 1 - dist / 12));
                hRow.push(64 + hill);
                cRow.push(0); // CLASS_GROUND
            }
        }
        heights.push(hRow);
        classes.push(cRow);
    }
    return { heights, classes };
}

{
    const { heights, classes } = buildTerrain40x40();
    const input = {
        from: [1000, 2000],
        to: [1039, 2039],
        heights,
        classes,
        surface: { "minecraft:grass_block": 1438, "minecraft:water": 81, "minecraft:oak_leaves": 81 }
    };
    save("relief-hill-lake-forest", input, renderRelief(input));
}

{
    // width=100 (>80) depth=50 (<=60) -> step=2 downsampling; area 5000 > MATRIX_AUTO_CELLS (1600) -> matrix omitted by default.
    const width = 100, depth = 50;
    const heights = [];
    const classes = [];
    for (let z = 0; z < depth; z++) {
        const hRow = [];
        const cRow = [];
        for (let x = 0; x < width; x++) {
            hRow.push(60 + Math.floor(x / 10));
            cRow.push(0);
        }
        heights.push(hRow);
        classes.push(cRow);
    }
    const input = { from: [0, 0], to: [width - 1, depth - 1], heights, classes, surface: { "minecraft:sand": width * depth } };
    save("relief-wide-downsampled", input, renderRelief(input));
}

{
    // A perfectly flat 5x5 area: buildLegend's min===max single-entry branch, matrix forced true.
    const size = 5;
    const heights = Array.from({ length: size }, () => new Array(size).fill(70));
    const classes = Array.from({ length: size }, () => new Array(size).fill(0));
    const input = { from: [-5, -5], to: [-1, -1], heights, classes, surface: { "minecraft:stone": 25 }, matrix: true };
    save("relief-flat-matrix", input, renderRelief(input));
}

// Also exercise buildLegend directly (its own JSON shape, not just embedded in renderRelief text).
save("relief-build-legend-bare", { min: 60, max: 68, hasLiquid: false, hasVegetation: false }, buildLegend(60, 68, false, false));
save("relief-build-legend-liquid-vegetation", { min: 60, max: 68, hasLiquid: true, hasVegetation: true }, buildLegend(60, 68, true, true));

// ---------------------------------------------------------------------
// rle.ts + slice.ts: a 9x9x9 hollow box region (stone shell, cobblestone floor, a
// glowstone light block, and a carved doorway), plus a wide strip region for
// slice's own step>1 downsampling path (no TS unit test covers that branch).
// ---------------------------------------------------------------------

function toRuns(sequence) {
    const runs = [];
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

function hollowBoxRegion() {
    const palette = ["minecraft:air", "minecraft:stone", "minecraft:cobblestone", "minecraft:glowstone"];
    const idx = { air: 0, stone: 1, cobble: 2, glow: 3 };
    const from = [100, 60, 200];
    const to = [108, 68, 208];
    const sequence = [];
    for (let ly = 0; ly <= 8; ly++) {
        for (let lz = 0; lz <= 8; lz++) {
            for (let lx = 0; lx <= 8; lx++) {
                let block;
                const isDoorway = lz === 8 && lx === 4 && (ly === 1 || ly === 2);
                if (isDoorway) {
                    block = idx.air;
                } else if (ly === 0) {
                    block = idx.cobble;
                } else if (lx === 0 || lx === 8 || lz === 0 || lz === 8 || ly === 8) {
                    block = idx.stone;
                } else if (lx === 4 && ly === 4 && lz === 4) {
                    block = idx.glow;
                } else {
                    block = idx.air;
                }
                sequence.push(block);
            }
        }
    }
    return { bounds: { from, to }, order: "y,z,x", palette, runs: toRuns(sequence), volume: sequence.length };
}

{
    const region = hollowBoxRegion();
    const decoded = decodeRegionData(region);
    save("inspect-stats-hollow-box", { region, world: "overworld" }, renderStats(decoded, "overworld"));

    const specs = [
        { axis: "y", at: 64 }, // mid-height top-down slice: ring with the interior glow block
        { axis: "x", at: 104 }, // vertical slice through the center: shows floor/shell/doorway silhouette
        { axis: "z", at: 208 } // vertical slice through the south (doorway) wall
    ];
    for (const spec of specs) {
        save(`inspect-slice-${spec.axis}-hollow-box`, { region, spec }, renderSlice(decoded, spec));
    }
}

function wideStripRegion() {
    // dx=90 (>80), dy=1, dz=3: axis="y" slice has rows=z(3) cols=x(90) -> step=2 (slice.ts's own downsample path).
    const palette = ["minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:emerald_block"];
    const from = [0, 0, 0];
    const to = [89, 0, 2];
    const sequence = [];
    for (let z = 0; z <= 2; z++) {
        for (let x = 0; x <= 89; x++) {
            if (x === 45 && z === 1) {
                sequence.push(3); // a single rare block to test frequency-sorted char assignment
            } else if (x % 4 < 3) {
                sequence.push(1);
            } else {
                sequence.push(2);
            }
        }
    }
    return { bounds: { from, to }, order: "y,z,x", palette, runs: toRuns(sequence), volume: sequence.length };
}

{
    const region = wideStripRegion();
    const decoded = decodeRegionData(region);
    const spec = { axis: "y", at: 0 };
    save("inspect-slice-y-wide-downsampled", { region, spec }, renderSlice(decoded, spec));
}

// ---------------------------------------------------------------------
// heightmap-view.ts (no dedicated TS unit test exists - the goldens are the
// only cross-language verification for this module's three functions)
// ---------------------------------------------------------------------

{
    const fields = {
        bounds: { from: [500, -200], to: [539, -161] },
        heights: { min: 58, max: 74, median: 65 },
        surface: { "minecraft:grass_block": 1000, "minecraft:water": 400, "minecraft:sand": 200 },
        flatZone: { x1: 505, z1: -195, x2: 520, z2: -180, y: 65, width: 16, depth: 16 },
        legend: [
            { color: "#3f76e4", label: "water" },
            { color: "#1b4d2e", label: "y 58-59" },
            { color: "#d9d9d9", label: "y 74" }
        ],
        liquidCells: 42,
        treeCells: 0,
        contour: 5
    };
    save("heightmap-summary-with-zone", fields, heightmapSummaryLine(fields));
    save("heightmap-legend-line", fields.legend, heightmapLegendLine(fields.legend));
    save("heightmap-contour-enabled", 5, heightmapContourLine(5));
}

{
    const fields = {
        bounds: { from: [0, 0], to: [19, 19] },
        heights: { min: 64, max: 64, median: 64 },
        surface: {},
        flatZone: null,
        legend: [{ color: "#1b4d2e", label: "y 64" }],
        liquidCells: 0,
        treeCells: 12,
        contour: 0
    };
    save("heightmap-summary-no-zone-with-trees", fields, heightmapSummaryLine(fields));
    save("heightmap-contour-disabled", 0, heightmapContourLine(0));
}

// ---------------------------------------------------------------------
// warnings.ts: three warning sets
// ---------------------------------------------------------------------

{
    // Set A: a mix of an untruncated single, an x-range group, a y-range group, and a gap - all in one call.
    const warnings = [
        { pos: [563, 66, -425], block: "minecraft:torch", reason: "embedded" }
    ];
    for (let x = 100; x <= 104; x++) {
        warnings.push({ pos: [x, 70, 200], block: "minecraft:oak_carpet", reason: "nothing solid below" });
    }
    for (let y = 65; y <= 82; y++) {
        warnings.push({
            pos: [561, y, -428],
            block: "minecraft:ladder[facing=east]",
            reason: "no support behind (facing=east needs a solid block at x-1)"
        });
    }
    warnings.push({ pos: [10, 64, 0], block: "minecraft:oak_carpet", reason: "nothing solid below" });
    warnings.push({ pos: [12, 64, 0], block: "minecraft:oak_carpet", reason: "nothing solid below" }); // gap at x=11
    save("warnings-set-a-mixed", { warnings, truncated: false }, formatWarnings(warnings, false));
}

{
    // Set B: an "L" shape of same block+reason - not reducible to one 2D group (docs comment in warnings.ts):
    // groups along x for z=0 (run of 2), leaving (0,64,1) as a lone single.
    const warnings = [
        { pos: [0, 64, 0], block: "minecraft:sand", reason: "nothing solid below" },
        { pos: [1, 64, 0], block: "minecraft:sand", reason: "nothing solid below" },
        { pos: [0, 64, 1], block: "minecraft:sand", reason: "nothing solid below" }
    ];
    save("warnings-set-b-l-shape", { warnings, truncated: false }, formatWarnings(warnings, false));
}

{
    // Set C: truncated=true together with multiple groups (not just a single warning, unlike the TS unit test).
    const warnings = [
        { pos: [0, 64, 0], block: "minecraft:torch", reason: "nothing solid below" },
        { pos: [0, 65, 0], block: "minecraft:torch", reason: "nothing solid below" },
        { pos: [0, 66, 0], block: "minecraft:torch", reason: "nothing solid below" },
        { pos: [200, 70, -50], block: "minecraft:torch", reason: "embedded" }
    ];
    save("warnings-set-c-truncated", { warnings, truncated: true }, formatWarnings(warnings, true));
}

// ---------------------------------------------------------------------
// errors.ts: every error code (plus BAD_REQUEST's two branches and an unknown code)
// ---------------------------------------------------------------------

const PLUGIN_URL = "ws://127.0.0.1:8765";
const ERROR_CASES = [
    ["VOLUME_EXCEEDED", "Requested volume 750000 exceeds the 500000-block limit."],
    ["INVALID_BLOCK", 'Unknown block state "minecraft:not_a_block".'],
    ["WORLD_NOT_ALLOWED", 'World "creative_backup" is not in the allowed list.'],
    ["OUT_OF_BUILD_REGION", "Coordinates [50000, 64, 0] are outside the configured build region."],
    ["QUEUE_FULL", "The operation queue is full (64/64 queued)."],
    ["DISABLED", "The snapshot feature is disabled."],
    ["UNAUTHORIZED", "invalid token."],
    ["UNAVAILABLE", "connect ECONNREFUSED 127.0.0.1:8765."],
    ["TIMEOUT", "Timed out after 30000ms waiting for a response."],
    ["BAD_REQUEST", "y range [500, -64] is outside world height limits."],
    ["BAD_REQUEST", "from and to must both be [x, z] or both be [x, y, z]."],
    ["SOME_FUTURE_CODE", "A code the client does not recognize yet."]
];
for (const [code, message] of ERROR_CASES) {
    const err = new PluginError(code, message);
    const name = `error-${code.toLowerCase()}${code === "BAD_REQUEST" ? (message.includes("y range [") ? "-yrange" : "-generic") : ""}`;
    save(name, { code, message, pluginUrl: PLUGIN_URL }, formatPluginError(err, PLUGIN_URL));
}

console.log(`\n${written} golden file(s) written to ${OUT_DIR}`);
