// SPDX-License-Identifier: AGPL-3.0-or-later
// Step 3 acceptance-criteria script (plan.md 3.5 item 2). Not part of the
// plugin build; lives outside plugin/src per the Step 3 prompt's hard rules.
// Reuses tools/client.mjs (Step 2).
//
// This script covers every check from plan.md 3.5 item 2 EXCEPT:
//   - the max-snapshots eviction check (needs a config edit + restart,
//     see verify_step3_max_snapshots.mjs)
//   - the run-command.enabled:false check (needs a config edit + restart,
//     see verify_step3_run_command.mjs, which also covers the enabled case)
//   - the restart-persistence check (needs an actual server restart,
//     see verify_step3_persistence.mjs, run after restarting the server)
//
// Run with: node tools/verify_step3.mjs
import { connect, call, authenticate } from "./client.mjs";

function log(title, obj) {
    console.log(`\n--- ${title} ---`);
    console.log(JSON.stringify(obj, null, 0));
}

async function main() {
    const { ws } = await connect();
    const authRes = await authenticate(ws);
    log("auth", authRes);

    // ------------------------------------------------------------------
    // Setup: clear a tall air column above the heightmap test area first,
    // so no engine-generated terrain above y=70 can interfere with the
    // "expect exactly y=70" assertions below. Also clears the read_region
    // all-air test area and a spare region used by the chunk-limit check.
    // ------------------------------------------------------------------
    const clear = await call(ws, "fill_batch", {
        world: "world",
        ops: [
            { from: [0, -64, 2000], to: [19, 150, 2019], block: "minecraft:air" },
            { from: [0, -64, 3000], to: [29, 150, 3009], block: "minecraft:air" },
            { from: [0, -64, 5000], to: [29, 150, 5029], block: "minecraft:air" },
        ],
    });
    log("setup: clear air columns", clear.response);

    // ------------------------------------------------------------------
    // Check: 20x20 stone slab at y=70 (x 0..19, z 2000..2019); heightmap
    // SOLID over the same range -> all 70, surface stone:400.
    // ------------------------------------------------------------------
    const platform = await call(ws, "fill_batch", {
        world: "world",
        ops: [{ from: [0, 70, 2000], to: [19, 70, 2019], block: "minecraft:stone" }],
    });
    log("stone platform y=70 20x20 (expect totalChanged=400)", platform.response);

    const hmSolid1 = await call(ws, "heightmap", { world: "world", from: [0, 2000], to: [19, 2019], type: "SOLID" });
    log("heightmap SOLID over platform (expect all heights=70, surface minecraft:stone=400)", hmSolid1.response);

    // ------------------------------------------------------------------
    // Check: water at (5,71,2005). SOLID heightmap at that point still 70;
    // SOLID_OR_LIQUID at that point 71.
    // ------------------------------------------------------------------
    const waterSet = await call(ws, "set_blocks", {
        world: "world",
        blocks: [{ pos: [5, 71, 2005], block: "minecraft:water" }],
    });
    log("set_blocks water at (5,71,2005) (expect changed=1)", waterSet.response);

    const hmSolid2 = await call(ws, "heightmap", { world: "world", from: [0, 2000], to: [19, 2019], type: "SOLID" });
    const point5x5z = hmSolid2.response.result.heights[5][5]; // heights[zi][xi], zi=z-2000=5, xi=x-0=5
    console.log(`\n--- heightmap SOLID after water, point (x=5,z=2005) ---\n${point5x5z} (expect 70)`);

    const hmSolidLiquid = await call(ws, "heightmap", {
        world: "world", from: [0, 2000], to: [19, 2019], type: "SOLID_OR_LIQUID",
    });
    const point5x5zLiquid = hmSolidLiquid.response.result.heights[5][5];
    console.log(`\n--- heightmap SOLID_OR_LIQUID after water, point (x=5,z=2005) ---\n${point5x5zLiquid} (expect 71)`);

    // ------------------------------------------------------------------
    // Check: heightmap area over max-read-volume (200000) -> VOLUME_EXCEEDED.
    // ------------------------------------------------------------------
    const hmTooBig = await call(ws, "heightmap", { world: "world", from: [0, 0], to: [999, 999] }); // 1,000,000
    log("heightmap area 1000000 (expect VOLUME_EXCEEDED)", hmTooBig.response);

    // ------------------------------------------------------------------
    // Check: snapshot a 10x5x10 region (also containing one oriented stair);
    // fill_batch overwrite with diamond_block; restore -> restored equals
    // the overwritten count; read_region -> no diamond_block in palette;
    // the oriented stair's exact state survives restore.
    // ------------------------------------------------------------------
    const snapRegion = { from: [20, 70, 3000], to: [29, 74, 3009] }; // 10x5x10 = 500
    const snapSetup = await call(ws, "fill_batch", { world: "world", ops: [{ ...snapRegion, block: "minecraft:stone" }] });
    log("snapshot region setup (stone, expect changed=500)", snapSetup.response);

    const stairPos = [25, 72, 3005];
    const stairSet = await call(ws, "set_blocks", {
        world: "world",
        blocks: [{ pos: stairPos, block: "minecraft:oak_stairs[facing=north,half=top]" }],
    });
    log("place oriented stair inside snapshot region (expect changed=1)", stairSet.response);

    const snapResult = await call(ws, "snapshot", { world: "world", ...snapRegion, label: "step3-verify" });
    log("snapshot (expect an id, volume=500)", snapResult.response);
    const snapshotId = snapResult.response.result.id;
    console.log(`\nSNAPSHOT ID FOR PERSISTENCE CHECK: ${snapshotId}`);

    const overwrite = await call(ws, "fill_batch", { world: "world", ops: [{ ...snapRegion, block: "minecraft:diamond_block" }] });
    log("overwrite snapshot region with diamond_block (expect changed=500)", overwrite.response);

    const restoreResult = await call(ws, "restore", { id: snapshotId });
    log("restore (expect restored=500, id echoed, queuedMs+elapsedMs present)", restoreResult.response);

    const readAfterRestore = await call(ws, "read_region", snapRegion);
    const paletteHasDiamond = readAfterRestore.response.result.palette.includes("minecraft:diamond_block");
    log("read_region after restore (expect palette WITHOUT minecraft:diamond_block)", readAfterRestore.response);
    console.log(`palette contains diamond_block: ${paletteHasDiamond} (expect false)`);

    const stairOrientationCheck = await call(ws, "fill_batch", {
        world: "world",
        ops: [{
            from: stairPos, to: stairPos, block: "minecraft:air",
            filter: "minecraft:oak_stairs[facing=north,half=top]",
        }],
    });
    log("stair orientation survived restore (expect changed=1)", stairOrientationCheck.response);
    // Put the stair back the way it was so the stored snapshot still matches the world for the persistence check.
    await call(ws, "set_blocks", { world: "world", blocks: [{ pos: stairPos, block: "minecraft:oak_stairs[facing=north,half=top]" }] });

    // ------------------------------------------------------------------
    // Check: list_snapshots includes the snapshot just created, newest first.
    // ------------------------------------------------------------------
    const listResult = await call(ws, "list_snapshots", {});
    log("list_snapshots (expect the step3-verify snapshot present)", listResult.response);

    // ------------------------------------------------------------------
    // Check: restore a nonexistent snapshot id -> BAD_REQUEST.
    // ------------------------------------------------------------------
    const restoreMissing = await call(ws, "restore", { id: "snap-does-not-exist-0000" });
    log("restore unknown id (expect BAD_REQUEST)", restoreMissing.response);

    // ------------------------------------------------------------------
    // Check: read_region on an all-air 30x10x30 region -> runs length 1,
    // palette = ["minecraft:air"].
    // ------------------------------------------------------------------
    const airRegion = { from: [0, 200, 5000], to: [29, 209, 5029] }; // 30x10x30 = 9000
    const airRead = await call(ws, "read_region", airRegion);
    log("read_region all-air 30x10x30 (expect runs.length=1, palette=[\"minecraft:air\"])", airRead.response);

    // ------------------------------------------------------------------
    // Check: run_command with run-command.enabled=true (default) dispatches.
    // ------------------------------------------------------------------
    const runCmd = await call(ws, "run_command", { command: "say hello from mcp" });
    log("run_command 'say hello from mcp' (expect dispatched=true)", runCmd.response);

    // ------------------------------------------------------------------
    // Extra (plan.md 2.6/3.1 deliverable, not in the literal 3.5 checklist):
    // limits.max-chunks-per-operation rejects a wide-but-thin op before it
    // ever force-loads thousands of chunks. A 1x1x5001 fill (volume 5001,
    // comfortably under max-blocks-per-operation) spans ~313 chunks, over
    // the default limit of 256.
    // ------------------------------------------------------------------
    const chunkLimit = await call(ws, "fill_batch", {
        world: "world",
        ops: [{ from: [0, 70, 9000], to: [0, 70, 14000], block: "minecraft:stone" }], // 1x1x5001, ~313 chunks
    });
    log("1x1x5001 thin fill exceeding max-chunks-per-operation (expect VOLUME_EXCEEDED naming chunks)", chunkLimit.response);

    // A shape within the chunk limit (1 wide x 4096 long, <=256 chunks) must
    // still succeed end-to-end, exercising FillTask's every-256-blocks
    // mid-row deadline check (plan.md 2.6) along the way.
    const longThinRow = await call(ws, "fill_batch", {
        world: "world",
        ops: [{ from: [0, 70, 6000], to: [4095, 70, 6000], block: "minecraft:stone" }], // 4096x1x1, 256 chunks
    });
    log("4096x1x1 row within the chunk limit (expect totalChanged=4096, no watchdog)", longThinRow.response);

    console.log("\nALL CHECKS SENT. Review the JSON above against plan.md 3.5 item 2.");
    process.exit(0);
}

main().catch((e) => {
    console.error("FATAL:", e);
    process.exit(1);
});
