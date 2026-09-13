// SPDX-License-Identifier: AGPL-3.0-or-later
// Standalone run_command check, used twice: once with run-command.enabled
// left at its default (true) and once after editing config.yml to
// run-command.enabled: false and restarting the server (plan.md 3.5 item 2).
// Usage: node tools/verify_step3_run_command.mjs "<say message>"
import { connect, call, authenticate } from "./client.mjs";

async function main() {
    const message = process.argv[2] || "say hello from mcp";
    const { ws } = await connect();
    await authenticate(ws);

    const result = await call(ws, "run_command", { command: message });
    console.log(JSON.stringify(result.response, null, 0));
    process.exit(0);
}

main().catch((e) => {
    console.error("FATAL:", e);
    process.exit(1);
});
