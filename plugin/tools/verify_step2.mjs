// SPDX-License-Identifier: AGPL-3.0-or-later
// Step 2 acceptance-criteria script (plan.md 2.4 item 2). Not part of the
// plugin build; lives outside plugin/src per the Step 2 prompt's hard rules.
// Run with: node tools/verify_step2.mjs
import { connect, call, authenticate } from "./client.mjs";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function log(title, obj) {
    console.log(`\n--- ${title} ---`);
    console.log(JSON.stringify(obj, null, 0));
}

async function main() {
    const { ws } = await connect();
    const authRes = await authenticate(ws);
    log("auth", authRes);

    // ------------------------------------------------------------------
    // Check 1: 50x20x50 = 50000 stone cube. changed=50000 first time, 0 on
    // repeat. At least one progress event. health.queuedOperations > 0
    // during execution, back to 0 after.
    // ------------------------------------------------------------------
    const zoneA = {
        world: "world",
        ops: [{ from: [0, -64, 0], to: [49, -45, 49], block: "minecraft:stone" }],
    };

    const fillAPromise = call(ws, "fill_batch", zoneA);
    await sleep(30); // let the executor pick it up before we poll health
    const healthDuring = await call(ws, "health", {});
    log("health DURING zoneA fill (expect queuedOperations > 0)", healthDuring.response);

    const zoneAResult = await fillAPromise;
    log("zoneA fill_batch (expect totalChanged=50000)", zoneAResult.response);
    console.log(`zoneA progress events received: ${zoneAResult.progress.length}`);
    log("zoneA progress sample", zoneAResult.progress.slice(0, 3));

    const healthAfter = await call(ws, "health", {});
    log("health AFTER zoneA fill (expect queuedOperations = 0)", healthAfter.response);

    const zoneARepeat = await call(ws, "fill_batch", zoneA);
    log("zoneA fill_batch REPEAT (expect totalChanged=0, idempotent)", zoneARepeat.response);

    // ------------------------------------------------------------------
    // Check 2: same batch clears to air then fills oak_log[axis=y] filtered
    // on minecraft:air; op1.changed must equal op0.changed.
    // ------------------------------------------------------------------
    const zoneBSetup = {
        world: "world",
        ops: [{ from: [60, -64, 0], to: [69, -55, 9], block: "minecraft:stone" }],
    };
    const zoneBSetupResult = await call(ws, "fill_batch", zoneBSetup);
    log("zoneB setup (stone)", zoneBSetupResult.response);

    const zoneBBatch = {
        world: "world",
        ops: [
            { from: [60, -64, 0], to: [69, -55, 9], block: "minecraft:air" },
            {
                from: [60, -64, 0], to: [69, -55, 9],
                block: "minecraft:oak_log[axis=y]", mode: "replace", filter: "minecraft:air",
            },
        ],
    };
    const zoneBResult = await call(ws, "fill_batch", zoneBBatch);
    log("zoneB air-then-oak_log batch (expect ops[0].changed == ops[1].changed == 1000)", zoneBResult.response);

    // ------------------------------------------------------------------
    // Check 3: mode=hollow on a 10x10x10 -> changed=1000. Verified by
    // pre-filling with a known block (stone) so every shell+interior cell
    // is guaranteed to differ from its pre-state, then confirming the
    // interior is air via a follow-up filtered fill_batch (no run_command
    // yet, so this is our "read" substitute).
    // ------------------------------------------------------------------
    const zoneCSetup = {
        world: "world",
        ops: [{ from: [80, -64, 0], to: [89, -55, 9], block: "minecraft:stone" }],
    };
    const zoneCSetupResult = await call(ws, "fill_batch", zoneCSetup);
    log("zoneC setup (stone)", zoneCSetupResult.response);

    const zoneCHollow = {
        world: "world",
        ops: [{ from: [80, -64, 0], to: [89, -55, 9], block: "minecraft:oak_planks", mode: "hollow" }],
    };
    const zoneCHollowResult = await call(ws, "fill_batch", zoneCHollow);
    log("zoneC hollow fill (expect totalChanged=1000)", zoneCHollowResult.response);

    const zoneCInteriorCheck = {
        world: "world",
        ops: [{
            from: [81, -63, 1], to: [88, -56, 8], block: "minecraft:diamond_block",
            filter: "minecraft:air",
        }],
    };
    const zoneCInteriorResult = await call(ws, "fill_batch", zoneCInteriorCheck);
    log("zoneC interior read-back (expect changed=512, proving interior was air)", zoneCInteriorResult.response);

    // ------------------------------------------------------------------
    // Check 4: set_blocks with an oriented stair; changed=1. Orientation
    // verified (no run_command yet) via a follow-up fill_batch with an
    // exact-state filter: BlockData#matches only succeeds if facing/half
    // match, so a changed=1 result proves the state survived.
    // ------------------------------------------------------------------
    const stairPos = [100, -60, 0];
    const setBlocksResult = await call(ws, "set_blocks", {
        world: "world",
        blocks: [{ pos: stairPos, block: "minecraft:oak_stairs[facing=north,half=top]" }],
    });
    log("set_blocks oriented stair (expect changed=1)", setBlocksResult.response);

    const stairCheck = await call(ws, "fill_batch", {
        world: "world",
        ops: [{
            from: stairPos, to: stairPos, block: "minecraft:air",
            filter: "minecraft:oak_stairs[facing=north,half=top]",
        }],
    });
    log("stair orientation read-back (expect changed=1, proves facing=north,half=top persisted)", stairCheck.response);

    // ------------------------------------------------------------------
    // Check 5: sand placed in mid-air must not fall (physics off). Verified
    // by a follow-up filtered fill_batch instead of run_command.
    // ------------------------------------------------------------------
    const sandRegion = { from: [0, 200, 100], to: [4, 200, 104] };
    const sandPlace = await call(ws, "fill_batch", {
        world: "world",
        ops: [{ ...sandRegion, block: "minecraft:sand" }],
    });
    log("sand placement in mid-air (expect changed=25)", sandPlace.response);

    console.log("waiting 2s of real time for any physics that would have fired...");
    await sleep(2000);

    const sandCheck = await call(ws, "fill_batch", {
        world: "world",
        ops: [{ ...sandRegion, block: "minecraft:air", filter: "minecraft:sand" }],
    });
    log("sand read-back (expect changed=25, proving sand never fell/disappeared)", sandCheck.response);

    // ------------------------------------------------------------------
    // Check 6: volume 600000 -> VOLUME_EXCEEDED, world untouched.
    // ------------------------------------------------------------------
    const tooBig = await call(ws, "fill_batch", {
        world: "world",
        ops: [{ from: [200, -64, 200], to: [299, -5, 299], block: "minecraft:stone" }], // 100*60*100=600000
    });
    log("volume 600000 (expect VOLUME_EXCEEDED)", tooBig.response);

    // ------------------------------------------------------------------
    // Check 7: mode: banana -> BAD_REQUEST; invalid block -> INVALID_BLOCK.
    // ------------------------------------------------------------------
    const badMode = await call(ws, "fill_batch", {
        world: "world",
        ops: [{ from: [110, -60, 0], to: [110, -60, 0], block: "minecraft:stone", mode: "banana" }],
    });
    log("mode: banana (expect BAD_REQUEST)", badMode.response);

    const badBlock = await call(ws, "fill_batch", {
        world: "world",
        ops: [{ from: [111, -60, 0], to: [111, -60, 0], block: "minecraft:not_a_block" }],
    });
    log("block: minecraft:not_a_block (expect INVALID_BLOCK)", badBlock.response);

    // ------------------------------------------------------------------
    // Check 8: world: "nether" -> WORLD_NOT_ALLOWED (not in allowed-worlds).
    // ------------------------------------------------------------------
    const badWorld = await call(ws, "fill_batch", {
        world: "nether",
        ops: [{ from: [0, -60, 0], to: [0, -60, 0], block: "minecraft:stone" }],
    });
    log("world: nether (expect WORLD_NOT_ALLOWED)", badWorld.response);

    // ------------------------------------------------------------------
    // Check 9: 17 large requests back-to-back -> the 17th gets QUEUE_FULL
    // (max-queued-operations=16, plus one currently running = 17 in flight
    // before the queue itself is full).
    // ------------------------------------------------------------------
    const bigOp = {
        world: "world",
        ops: [{ from: [0, -64, 5000], to: [99, 25, 5054], block: "minecraft:andesite" }], // 100*90*55=495000
    };
    const pending = [];
    for (let i = 0; i < 16; i++) {
        pending.push(call(ws, "fill_batch", bigOp, `queue-fill-${i}`));
    }
    // Give the first send a brief head start so it's already "current" by
    // the time we probe with the 17th, then fire the 17th immediately.
    await sleep(5);
    const the17th = await call(ws, "fill_batch", bigOp, "queue-fill-17th");
    log("17th large request while 16 are queued (expect QUEUE_FULL)", the17th.response);

    console.log("\nwaiting for the 16 queued large fills to finish (health.queuedOperations should reach 0)...");
    for (let i = 0; i < 120; i++) {
        const h = await call(ws, "health", {});
        if (h.response.result.queuedOperations === 0) {
            console.log(`queuedOperations back to 0 after ~${i * 0.5}s of polling`);
            break;
        }
        await sleep(500);
    }
    const results = await Promise.all(pending);
    console.log(`all 16 queued large fills completed; changed values: ${results.map((r) => r.response.result?.totalChanged).join(",")}`);

    const finalHealth = await call(ws, "health", {});
    log("final health (expect queuedOperations = 0)", finalHealth.response);

    console.log("\nALL CHECKS SENT. Review the JSON above against plan.md 2.4 item 2.");
    process.exit(0);
}

main().catch((e) => {
    console.error("FATAL:", e);
    process.exit(1);
});
