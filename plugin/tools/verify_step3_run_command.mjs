// SPDX-License-Identifier: AGPL-3.0-or-later
// Standalone run_command check, used twice: once with run-command.enabled
// left at its default (true) and once after editing config.yml to
// run-command.enabled: false and restarting the server (plan.md 3.5 item 2).
//
// With no arguments, runs two feedback-capture assertions (step 4.6):
//   - "time query day" -> output contains "<n> tick(s)" (a numeric daytime
//     reading). NOTE: on this Paper build (26.2) the query keyword is "day",
//     not "daytime" as in some older/vanilla docs - "time query daytime"
//     throws a CommandSyntaxException ("Can't find element
//     'minecraft:daytime' of type 'minecraft:timeline'"), confirmed by
//     hand against the live test server. "day" is the working keyword here.
//   - "list"            -> output contains "players online"
// ("say ..." is not used for the assertion: it broadcasts to players/log but
// the command itself may send no feedback back to the sender.)
//
// Pass a single command as argv[2] to run just that command with no
// assertion (used for the run-command.enabled:false check).
// Usage: node tools/verify_step3_run_command.mjs
//        node tools/verify_step3_run_command.mjs "<command>"
import { connect, call, authenticate } from "./client.mjs";

function log(title, obj) {
    console.log(`\n--- ${title} ---`);
    console.log(JSON.stringify(obj, null, 0));
}

async function main() {
    const { ws } = await connect();
    await authenticate(ws);

    if (process.argv[2]) {
        const result = await call(ws, "run_command", { command: process.argv[2] });
        console.log(JSON.stringify(result.response, null, 0));
        process.exit(0);
    }

    let failures = 0;

    const timeResult = await call(ws, "run_command", { command: "time query day" });
    log('run_command "time query day" (expect output containing "<n> tick(s)")', timeResult.response);
    const timeOutput = (timeResult.response?.result?.output ?? []).join("\n");
    if (!/\d+ tick\(s\)/.test(timeOutput)) {
        console.log(`  [FAIL] expected output to contain "<n> tick(s)", got: ${JSON.stringify(timeOutput)}`);
        failures++;
    } else {
        console.log('  [ok] output contains "<n> tick(s)"');
    }

    const listResult = await call(ws, "run_command", { command: "list" });
    log('run_command "list" (expect output containing "players online")', listResult.response);
    const listOutput = (listResult.response?.result?.output ?? []).join("\n");
    if (!/players online/.test(listOutput)) {
        console.log(`  [FAIL] expected output to contain "players online", got: ${JSON.stringify(listOutput)}`);
        failures++;
    } else {
        console.log("  [ok] output contains \"players online\"");
    }

    if (failures > 0) {
        console.log(`\nverify_step3_run_command: ${failures} check(s) FAILED`);
        process.exit(1);
    }
    console.log("\nverify_step3_run_command: all checks passed");
    process.exit(0);
}

main().catch((e) => {
    console.error("FATAL:", e);
    process.exit(1);
});
