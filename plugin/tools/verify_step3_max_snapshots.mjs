// SPDX-License-Identifier: AGPL-3.0-or-later
// max-snapshots eviction check (plan.md 3.5 item 2). Run this AFTER editing
// plugins/McAiBuilder/config.yml to set snapshot.max-snapshots: 2 and
// restarting the server. Creates 3 distinct snapshots; the 3rd creation
// should evict the 1st (oldest). Cross-check the snapshots/ directory's
// file count against list_snapshots afterwards (done by the caller in bash).
// Usage: node tools/verify_step3_max_snapshots.mjs
import { connect, call, authenticate } from "./client.mjs";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function log(title, obj) {
    console.log(`\n--- ${title} ---`);
    console.log(JSON.stringify(obj, null, 0));
}

async function main() {
    const { ws } = await connect();
    await authenticate(ws);

    // Three tiny, distinct 1x1x1 regions so each snapshot's content differs.
    for (let i = 0; i < 3; i++) {
        const pos = { from: [500 + i, 70, 500], to: [500 + i, 70, 500] };
        await call(ws, "fill_batch", { world: "world", ops: [{ ...pos, block: "minecraft:stone" }] });
        const snap = await call(ws, "snapshot", { world: "world", ...pos, label: `max-snapshots-check-${i}` });
        log(`snapshot #${i}`, snap.response);
        // Ensure strictly increasing createdAt/id timestamps between snapshots.
        await sleep(1100);
    }

    const list = await call(ws, "list_snapshots", {});
    log("list_snapshots after 3 creations with max-snapshots=2 (expect exactly 2 entries, oldest evicted)", list.response);
    console.log(`\nsnapshot count: ${list.response.result.snapshots.length} (expect 2)`);

    process.exit(0);
}

main().catch((e) => {
    console.error("FATAL:", e);
    process.exit(1);
});
