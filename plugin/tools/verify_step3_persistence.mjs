// SPDX-License-Identifier: AGPL-3.0-or-later
// Restart-persistence check (plan.md 3.5 item 2). Run this AFTER stopping
// and restarting the server (a plain restart, no config edits): confirms
// list_snapshots still shows the snapshot created before the restart and
// that restore still works against it (disk persistence took effect).
// Usage: node tools/verify_step3_persistence.mjs <snapshot-id>
import { connect, call, authenticate } from "./client.mjs";

async function main() {
    const snapshotId = process.argv[2];
    if (!snapshotId) {
        console.error("usage: node tools/verify_step3_persistence.mjs <snapshot-id>");
        process.exit(1);
    }

    const { ws } = await connect();
    await authenticate(ws);

    const list = await call(ws, "list_snapshots", {});
    console.log("\n--- list_snapshots after restart ---");
    console.log(JSON.stringify(list.response, null, 0));
    const found = list.response.result.snapshots.some((s) => s.id === snapshotId);
    console.log(`\nsnapshot ${snapshotId} present after restart: ${found} (expect true)`);

    const restore = await call(ws, "restore", { id: snapshotId });
    console.log("\n--- restore after restart ---");
    console.log(JSON.stringify(restore.response, null, 0));

    process.exit(0);
}

main().catch((e) => {
    console.error("FATAL:", e);
    process.exit(1);
});
