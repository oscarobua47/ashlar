#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// End-to-end verification script (plan.md section 4.5.2/4.5.3). Spawns the
// built dist/cli.js in --stdio mode as a real MCP client would, and drives
// it through every mc_* tool against a running plugin test server. Prints
// each tool's text output verbatim so it can be pasted into a verification
// report. Also spawns a second dist/cli.js in --http mode to check the
// Authorization-header and token-in-path authentication paths.
//
// Usage:
//   cd mcp-server && npm run build
//   MC_PLUGIN_URL=ws://127.0.0.1:8765 MC_PLUGIN_TOKEN=<token> node tools/e2e.mjs

import { fileURLToPath } from "node:url";
import path from "node:path";
import { spawn } from "node:child_process";

import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport, getDefaultEnvironment } from "@modelcontextprotocol/client/stdio";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const distCli = path.join(__dirname, "..", "dist", "cli.js");

const MC_PLUGIN_URL = process.env.MC_PLUGIN_URL ?? "ws://127.0.0.1:8765";
const MC_PLUGIN_TOKEN = process.env.MC_PLUGIN_TOKEN ?? "24e77d575101fd68043ba698c67bf45d";
// Distinct from the plugin server's default port 3000, since a developer's
// machine commonly already has another mc-ai-builder-mcp --http instance
// bound there (plan.md 4.5.2 HTTP checks).
const MCP_HTTP_PORT = process.env.MCP_HTTP_PORT ?? "3100";
const MCP_HTTP_TOKEN = process.env.MCP_HTTP_TOKEN ?? "http-test-token-0123456789";

let failures = 0;

function section(title) {
    console.log(`\n${"=".repeat(80)}\n${title}\n${"=".repeat(80)}`);
}

function check(label, condition) {
    if (condition) {
        console.log(`  [ok] ${label}`);
    } else {
        console.log(`  [FAIL] ${label}`);
        failures++;
    }
}

function textOf(result) {
    return result.content.map(c => (c.type === "text" ? c.text : `[${c.type}]`)).join("\n");
}

// A successful POST /mcp response body is SSE-framed ("event: message\ndata:
// {...}\n\n") even with responseMode "json" configured server-side, so pull
// the JSON-RPC payload out of the last "data:" line rather than parsing the
// body directly as JSON.
function parseMcpResponseBody(text) {
    const dataLines = text
        .split("\n")
        .filter(line => line.startsWith("data: "))
        .map(line => line.slice("data: ".length));
    const payload = dataLines.length > 0 ? dataLines[dataLines.length - 1] : text;
    return JSON.parse(payload);
}

// --- HTTP mode: bearer header vs. token-in-path (plan.md 4.5.2) -----------
async function runHttpChecks() {
    section("HTTP mode (Authorization header vs. token-in-path)");

    const child = spawn(process.execPath, [distCli, "--http"], {
        env: {
            ...getDefaultEnvironment(),
            MC_PLUGIN_URL,
            MC_PLUGIN_TOKEN,
            MCP_HTTP_TOKEN,
            MCP_HTTP_PORT
        },
        stdio: ["ignore", "ignore", "pipe"]
    });

    let stderrBuf = "";
    const ready = new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("timed out waiting for the HTTP server to start")), 10_000);
        child.stderr.on("data", chunk => {
            stderrBuf += chunk.toString();
            if (stderrBuf.includes("serving over HTTP")) {
                clearTimeout(timer);
                resolve();
            }
        });
        child.on("exit", code => {
            clearTimeout(timer);
            reject(new Error(`HTTP server process exited early (code=${code}); stderr: ${stderrBuf}`));
        });
    });

    try {
        await ready;

        const base = `http://127.0.0.1:${MCP_HTTP_PORT}`;
        const body = JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list", params: {} });
        const jsonHeaders = { "content-type": "application/json", accept: "application/json, text/event-stream" };

        const withHeader = await fetch(`${base}/mcp`, {
            method: "POST",
            headers: { ...jsonHeaders, authorization: `Bearer ${MCP_HTTP_TOKEN}` },
            body
        });
        await withHeader.text();
        check(`/mcp with correct Authorization header -> 200 (got ${withHeader.status})`, withHeader.status === 200);

        const noHeader = await fetch(`${base}/mcp`, { method: "POST", headers: jsonHeaders, body });
        await noHeader.text();
        check(`/mcp with no header -> 401 (got ${noHeader.status})`, noHeader.status === 401);

        const pathToken = await fetch(`${base}/mcp/${MCP_HTTP_TOKEN}`, { method: "POST", headers: jsonHeaders, body });
        const pathTokenText = await pathToken.text();
        check(`/mcp/<token> with no header -> 200 (got ${pathToken.status})`, pathToken.status === 200);
        let toolCount = null;
        try {
            toolCount = parseMcpResponseBody(pathTokenText)?.result?.tools?.length ?? null;
        } catch {
            // reported as a failed check below
        }
        check(`/mcp/<token> tools/list response lists 8 tools (got ${toolCount})`, toolCount === 8);

        const wrongToken = await fetch(`${base}/mcp/wrong-token`, { method: "POST", headers: jsonHeaders, body });
        await wrongToken.text();
        check(`/mcp/wrong-token -> 401 (got ${wrongToken.status})`, wrongToken.status === 401);
    } finally {
        child.kill("SIGTERM");
        await new Promise(resolve => child.once("exit", resolve));
    }
}

async function main() {
    const transport = new StdioClientTransport({
        command: process.execPath,
        args: [distCli, "--stdio"],
        env: {
            ...getDefaultEnvironment(),
            MC_PLUGIN_URL,
            MC_PLUGIN_TOKEN
        }
    });

    const client = new Client({ name: "mc-ai-builder-e2e", version: "0.1.0" });
    await client.connect(transport);

    // --- tools/list -------------------------------------------------------
    section("tools/list");
    const { tools } = await client.listTools();
    check("exactly 8 tools", tools.length === 8);
    const expectedNames = [
        "mc_status",
        "mc_players",
        "mc_survey",
        "mc_build",
        "mc_inspect",
        "mc_snapshot",
        "mc_restore",
        "mc_command"
    ];
    for (const name of expectedNames) {
        check(`tool "${name}" present`, tools.some(t => t.name === name));
    }
    for (const tool of tools) {
        const words = tool.description.trim().split(/\s+/).length;
        console.log(`  ${tool.name}: ${words} words`);
        check(`${tool.name} description >= 150 words`, words >= 150);
        check(`${tool.name} description <= 350 words`, words <= 350);
        check(`${tool.name} description contains "WHEN TO USE"`, tool.description.includes("WHEN TO USE"));
        check(`${tool.name} description contains "WHEN NOT TO USE"`, tool.description.includes("WHEN NOT TO USE"));
        check(`${tool.name} description contains "SIDE EFFECTS"`, tool.description.includes("SIDE EFFECTS"));
    }

    // --- mc_status ----------------------------------------------------------
    section("mc_status");
    const status = await client.callTool({ name: "mc_status", arguments: {} });
    console.log(textOf(status));
    check("mc_status not an error", !status.isError);
    check("mc_status mentions plugin version", /Plugin version:/.test(textOf(status)));

    // --- mc_players -----------------------------------------------------
    section("mc_players");
    const playersResult = await client.callTool({ name: "mc_players", arguments: {} });
    const playersText = textOf(playersResult);
    console.log(playersText);
    check("mc_players not an error", !playersResult.isError);
    check('mc_players reports "No players online." on the empty test server', playersText.trim() === "No players online.");

    // --- mc_build: platform + hollow tower + stairs, with snapshot ---------
    section('mc_build (platform + hollow tower + stairs, snapshot:true)');
    const BASE_X = 300;
    const BASE_Z = 300;
    const buildResult = await client.callTool({
        name: "mc_build",
        arguments: {
            snapshot: true,
            fills: [
                { from: [BASE_X, 64, BASE_Z], to: [BASE_X + 59, 64, BASE_Z + 59], block: "minecraft:stone" },
                {
                    from: [BASE_X + 25, 65, BASE_Z + 25],
                    to: [BASE_X + 34, 70, BASE_Z + 34],
                    block: "minecraft:stone_bricks",
                    mode: "hollow"
                }
            ],
            blocks: [
                { pos: [BASE_X + 25, 65, BASE_Z + 29], block: "minecraft:stone_brick_stairs[facing=south]" },
                { pos: [BASE_X + 34, 65, BASE_Z + 29], block: "minecraft:stone_brick_stairs[facing=north]" },
                { pos: [BASE_X + 29, 65, BASE_Z + 25], block: "minecraft:stone_brick_stairs[facing=east]" },
                { pos: [BASE_X + 29, 65, BASE_Z + 34], block: "minecraft:stone_brick_stairs[facing=west]" }
            ]
        }
    });
    const buildText = textOf(buildResult);
    console.log(buildText);
    check("mc_build not an error", !buildResult.isError);
    const snapshotMatch = buildText.match(/Snapshot (snap-[\w-]+) created/);
    check("mc_build response includes a snapshot id", !!snapshotMatch);
    const snapshotId = snapshotMatch ? snapshotMatch[1] : null;

    // --- mc_survey over the same area ---------------------------------------
    section("mc_survey (same area)");
    const surveyResult = await client.callTool({
        name: "mc_survey",
        arguments: { from: [BASE_X, BASE_Z], to: [BASE_X + 59, BASE_Z + 59] }
    });
    const surveyText = textOf(surveyResult);
    console.log(surveyText);
    check("mc_survey not an error", !surveyResult.isError);
    check("mc_survey output contains a legend", /Legend:/.test(surveyText));
    check("mc_survey output contains largest flat zone line", /Largest flat zone/.test(surveyText));

    // --- mc_inspect: slice through the tower --------------------------------
    section("mc_inspect (slice y=66 through the tower)");
    const inspectSliceResult = await client.callTool({
        name: "mc_inspect",
        arguments: {
            from: [BASE_X + 25, 66, BASE_Z + 25],
            to: [BASE_X + 34, 66, BASE_Z + 34],
            slice: { axis: "y", at: 66 }
        }
    });
    const inspectSliceText = textOf(inspectSliceResult);
    console.log(inspectSliceText);
    check("mc_inspect (slice) not an error", !inspectSliceResult.isError);
    check("mc_inspect (slice) contains a legend", /Legend:/.test(inspectSliceText));

    // --- mc_restore -----------------------------------------------------------
    section("mc_restore");
    if (snapshotId) {
        const restoreResult = await client.callTool({ name: "mc_restore", arguments: { id: snapshotId } });
        const restoreText = textOf(restoreResult);
        console.log(restoreText);
        check("mc_restore not an error", !restoreResult.isError);
        check("mc_restore restored > 0 blocks", /Restored snapshot .*: [1-9]/.test(restoreText));

        section("mc_inspect (after restore, no slice)");
        const afterRestore = await client.callTool({
            name: "mc_inspect",
            arguments: { from: [BASE_X, 64, BASE_Z], to: [BASE_X + 59, 70, BASE_Z + 59] }
        });
        const afterRestoreText = textOf(afterRestore);
        console.log(afterRestoreText);
        check("no stone_bricks left after restore", !/minecraft:stone_bricks/.test(afterRestoreText));
    } else {
        console.log("  skipped: no snapshot id captured from mc_build");
        failures++;
    }

    // --- mc_snapshot list -------------------------------------------------
    section("mc_snapshot (list)");
    const listResult = await client.callTool({ name: "mc_snapshot", arguments: { action: "list" } });
    console.log(textOf(listResult));
    check("mc_snapshot list not an error", !listResult.isError);

    // --- mc_command ---------------------------------------------------------
    section("mc_command");
    const cmdResult = await client.callTool({ name: "mc_command", arguments: { command: "say hi from mc-ai-builder e2e" } });
    const cmdText = textOf(cmdResult);
    console.log(cmdText);
    check("mc_command not an error", !cmdResult.isError);
    check("mc_command dispatched", /dispatched\b/.test(cmdText) && !/not dispatched/.test(cmdText));

    // --- mc_build errors ------------------------------------------------------
    section("mc_build (error: volume exceeded)");
    const tooBig = await client.callTool({
        name: "mc_build",
        arguments: { fills: [{ from: [0, -60, 0], to: [999, 0, 599], block: "minecraft:stone" }] }
    });
    console.log(textOf(tooBig));
    check("volume-exceeded call isError", tooBig.isError === true);
    check('volume-exceeded message contains "Split the work"', /Split the work/.test(textOf(tooBig)));

    section("mc_build (error: invalid block)");
    const badBlock = await client.callTool({
        name: "mc_build",
        arguments: { fills: [{ from: [BASE_X, 64, BASE_Z], to: [BASE_X, 64, BASE_Z], block: "minecraft:nope" }] }
    });
    console.log(textOf(badBlock));
    check("invalid-block call isError", badBlock.isError === true);
    check('invalid-block message contains "not a valid block state"', /not a valid block state/.test(textOf(badBlock)));

    await client.close();

    try {
        await runHttpChecks();
    } catch (err) {
        console.log(`  [FAIL] HTTP checks: ${err.message}`);
        failures++;
    }

    console.log(`\n${"=".repeat(80)}`);
    if (failures > 0) {
        console.log(`e2e: ${failures} check(s) FAILED`);
        process.exit(1);
    } else {
        console.log("e2e: all checks passed");
    }
}

main().catch(err => {
    console.error("e2e: fatal error", err);
    process.exit(1);
});
