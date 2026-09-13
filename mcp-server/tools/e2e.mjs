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
import fs from "node:fs";
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
        check(`/mcp/<token> tools/list response lists 9 tools (got ${toolCount})`, toolCount === 9);

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
    check("exactly 9 tools", tools.length === 9);
    const expectedNames = [
        "mc_status",
        "mc_players",
        "mc_survey",
        "mc_build",
        "mc_inspect",
        "mc_render",
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

    // --- mc_render: top + south + slice, while the tower still stands -------
    const outDir = path.join(__dirname, "out");
    fs.mkdirSync(outDir, { recursive: true });

    function checkRenderResult(result, label, pngPath) {
        check(`${label} not an error`, !result.isError);
        const text = textOf(result);
        console.log(text);
        const imageBlock = result.content.find(c => c.type === "image");
        check(`${label} returned an image content block`, !!imageBlock);
        if (!imageBlock) {
            failures++;
            return;
        }
        const bytes = Buffer.from(imageBlock.data, "base64");
        fs.writeFileSync(pngPath, bytes);
        check(`${label} PNG starts with the PNG magic bytes`, bytes.length > 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47);
        check(`${label} PNG size is sane (500 bytes - 3MB), got ${bytes.length}`, bytes.length > 500 && bytes.length <= 3 * 1024 * 1024);
        const sizeMatch = text.match(/Size: (\d+)x(\d+)px/);
        check(`${label} text reports a Size: WxHpx line`, !!sizeMatch);
        if (sizeMatch) {
            const width = Number(sizeMatch[1]);
            const height = Number(sizeMatch[2]);
            check(`${label} width is sane (1-1200), got ${width}`, width > 0 && width <= 1200);
            check(`${label} height is sane (1-1200), got ${height}`, height > 0 && height <= 1200);
        }
        check(`${label} text has a Legend section`, /Legend/.test(text));
        return { text, bytes };
    }

    section("mc_render (top view of the tower)");
    const renderTop = await client.callTool({
        name: "mc_render",
        arguments: { from: [BASE_X, 60, BASE_Z], to: [BASE_X + 59, 75, BASE_Z + 59], view: "top" }
    });
    const topOut = checkRenderResult(renderTop, "mc_render top", path.join(outDir, "top.png"));
    if (topOut) {
        check("mc_render top text mentions View: top", /View: top/.test(topOut.text));
    }

    section("mc_render (south facade of the tower)");
    const renderSouth = await client.callTool({
        name: "mc_render",
        arguments: { from: [BASE_X + 20, 64, BASE_Z + 20], to: [BASE_X + 39, 75, BASE_Z + 39], view: "south" }
    });
    const southOut = checkRenderResult(renderSouth, "mc_render south", path.join(outDir, "south.png"));
    if (southOut) {
        check("mc_render south text mentions View: south", /View: south/.test(southOut.text));
    }

    section("mc_render (slice y=66 through the tower ring)");
    const renderSlice = await client.callTool({
        name: "mc_render",
        arguments: {
            from: [BASE_X + 24, 66, BASE_Z + 24],
            to: [BASE_X + 35, 66, BASE_Z + 35],
            view: "slice",
            slice: { axis: "y", at: 66 }
        }
    });
    const sliceOut = checkRenderResult(renderSlice, "mc_render slice", path.join(outDir, "slice.png"));
    if (sliceOut) {
        check("mc_render slice text mentions View: slice", /View: slice/.test(sliceOut.text));
    }

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

    // --- step4d Fix 1: mode "walls" ------------------------------------------
    section('mc_build (mode: "walls", 7x4x7)');
    const WALLS_X = 450;
    const WALLS_Y = 64;
    const WALLS_Z = 450;
    const wallsBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            fills: [
                // Clear to air first: this is unmanaged natural terrain, not a
                // superflat test world, so the region may already contain solid
                // ground. Clearing first makes "interior untouched" verifiable
                // as "interior is air" below, and does not affect the walls
                // op's own changed-count check (it is a separate op).
                {
                    from: [WALLS_X, WALLS_Y, WALLS_Z],
                    to: [WALLS_X + 6, WALLS_Y + 3, WALLS_Z + 6],
                    block: "minecraft:air"
                },
                {
                    from: [WALLS_X, WALLS_Y, WALLS_Z],
                    to: [WALLS_X + 6, WALLS_Y + 3, WALLS_Z + 6],
                    block: "minecraft:cobblestone",
                    mode: "walls"
                }
            ]
        }
    });
    const wallsBuildText = textOf(wallsBuild);
    console.log(wallsBuildText);
    check("mc_build walls not an error", !wallsBuild.isError);
    check("mc_build walls changed 96/196 (2*7*4 + 2*5*4 for a 7x7 footprint, 4 tall)", /96\/196 changed/.test(wallsBuildText));

    section("mc_inspect (walls, slice y=65 mid height: expect a ring with air inside)");
    const wallsMidY = WALLS_Y + 1;
    const wallsSlice = await client.callTool({
        name: "mc_inspect",
        arguments: {
            from: [WALLS_X, wallsMidY, WALLS_Z],
            to: [WALLS_X + 6, wallsMidY, WALLS_Z + 6],
            slice: { axis: "y", at: wallsMidY }
        }
    });
    const wallsSliceText = textOf(wallsSlice);
    console.log(wallsSliceText);
    check("mc_inspect walls slice not an error", !wallsSlice.isError);
    check("walls slice interior is air (legend lists minecraft:air)", /\. minecraft:air/.test(wallsSliceText));
    check("walls slice legend shows cobblestone as the only non-air block", /minecraft:cobblestone/.test(wallsSliceText));
    check("walls slice legend has exactly one non-air block (a clean ring, no terrain leftovers)", (wallsSliceText.match(/minecraft:/g) ?? []).length === 2);

    section("mc_inspect (walls, floor layer y=64: expect the interior also air, unlike hollow)");
    const wallsFloorSlice = await client.callTool({
        name: "mc_inspect",
        arguments: {
            from: [WALLS_X, WALLS_Y, WALLS_Z],
            to: [WALLS_X + 6, WALLS_Y, WALLS_Z + 6],
            slice: { axis: "y", at: WALLS_Y }
        }
    });
    const wallsFloorSliceText = textOf(wallsFloorSlice);
    console.log(wallsFloorSliceText);
    check(
        "walls floor layer interior is air too, unlike hollow (legend lists minecraft:air)",
        /\. minecraft:air/.test(wallsFloorSliceText)
    );

    // --- step4d Fix 2: connectable blocks --------------------------------------
    section("mc_build (glass pane row between stone walls, connect default true)");
    const PANE_X = 460;
    const PANE_Y = 70;
    const PANE_Z = 460;
    const paneBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            fills: [
                { from: [PANE_X, PANE_Y, PANE_Z], to: [PANE_X + 2, PANE_Y, PANE_Z], block: "minecraft:stone" },
                { from: [PANE_X + 6, PANE_Y, PANE_Z], to: [PANE_X + 8, PANE_Y, PANE_Z], block: "minecraft:stone" }
            ],
            blocks: [
                { pos: [PANE_X + 3, PANE_Y, PANE_Z], block: "minecraft:glass_pane" },
                { pos: [PANE_X + 4, PANE_Y, PANE_Z], block: "minecraft:glass_pane" },
                { pos: [PANE_X + 5, PANE_Y, PANE_Z], block: "minecraft:glass_pane" }
            ]
        }
    });
    console.log(textOf(paneBuild));
    check("mc_build pane row not an error", !paneBuild.isError);

    section("mc_inspect (pane row slice, connect default true: expect connected panes)");
    const paneSlice = await client.callTool({
        name: "mc_inspect",
        arguments: {
            from: [PANE_X, PANE_Y, PANE_Z],
            to: [PANE_X + 8, PANE_Y, PANE_Z],
            slice: { axis: "y", at: PANE_Y }
        }
    });
    const paneSliceText = textOf(paneSlice);
    console.log(paneSliceText);
    check("connected pane slice not an error", !paneSlice.isError);

    section("mc_inspect (pane row, no slice: block-frequency table shows connected pane states)");
    const paneStats = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [PANE_X, PANE_Y, PANE_Z], to: [PANE_X + 8, PANE_Y, PANE_Z] }
    });
    const paneStatsText = textOf(paneStats);
    console.log(paneStatsText);
    check(
        "pane states show at least one connected face (east=true or west=true)",
        /glass_pane\[[^\]]*(east=true|west=true)/.test(paneStatsText)
    );
    check(
        "the two outer panes connect to the stone wall (both east=true and west=true appear across entries)",
        /east=true/.test(paneStatsText) && /west=true/.test(paneStatsText)
    );

    section("mc_build (same pane row layout, connect:false, fresh area)");
    const PANE2_X = 470;
    const paneBuildNoConnect = await client.callTool({
        name: "mc_build",
        arguments: {
            connect: false,
            fills: [
                { from: [PANE2_X, PANE_Y, PANE_Z], to: [PANE2_X + 2, PANE_Y, PANE_Z], block: "minecraft:stone" },
                { from: [PANE2_X + 6, PANE_Y, PANE_Z], to: [PANE2_X + 8, PANE_Y, PANE_Z], block: "minecraft:stone" }
            ],
            blocks: [
                { pos: [PANE2_X + 3, PANE_Y, PANE_Z], block: "minecraft:glass_pane" },
                { pos: [PANE2_X + 4, PANE_Y, PANE_Z], block: "minecraft:glass_pane" },
                { pos: [PANE2_X + 5, PANE_Y, PANE_Z], block: "minecraft:glass_pane" }
            ]
        }
    });
    console.log(textOf(paneBuildNoConnect));
    check("mc_build pane row (connect:false) not an error", !paneBuildNoConnect.isError);

    section("mc_inspect (pane row, connect:false: expect all panes unconnected)");
    const paneStatsNoConnect = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [PANE2_X, PANE_Y, PANE_Z], to: [PANE2_X + 8, PANE_Y, PANE_Z] }
    });
    const paneStatsNoConnectText = textOf(paneStatsNoConnect);
    console.log(paneStatsNoConnectText);
    check(
        "connect:false leaves panes fully unconnected (east=false,...,west=false only)",
        /glass_pane\[east=false,north=false,south=false,waterlogged=false,west=false\]/.test(paneStatsNoConnectText) &&
            !/east=true/.test(paneStatsNoConnectText) &&
            !/west=true/.test(paneStatsNoConnectText)
    );

    // --- step4d Fix 3: sign text ------------------------------------------------
    section("mc_build (oak_wall_sign with sign.front text)");
    const SIGN_X = 480;
    const SIGN_Y = 70;
    const SIGN_Z = 480;
    const signBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            blocks: [
                { pos: [SIGN_X, SIGN_Y, SIGN_Z - 1], block: "minecraft:cobblestone" },
                {
                    pos: [SIGN_X, SIGN_Y, SIGN_Z],
                    block: "minecraft:oak_wall_sign[facing=south]",
                    sign: { front: ["Made by", "MC AI Builder"] }
                }
            ]
        }
    });
    console.log(textOf(signBuild));
    check("mc_build sign not an error", !signBuild.isError);

    section("mc_inspect (over the sign: expect a Signs: section with the text)");
    const signInspect = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [SIGN_X, SIGN_Y, SIGN_Z - 1], to: [SIGN_X, SIGN_Y, SIGN_Z] }
    });
    const signInspectText = textOf(signInspect);
    console.log(signInspectText);
    check("mc_inspect over the sign not an error", !signInspect.isError);
    check("mc_inspect output contains a Signs: section", /Signs:/.test(signInspectText));
    check("Signs: line contains the front text", /Made by.*MC AI Builder/.test(signInspectText));

    // --- step4d Fix 2 regression: sand next to a fence must still not fall -----
    section("mc_build (regression: sand next to a fence in the same batch)");
    const SAND_X = 490;
    const SAND_Y = 90;
    const SAND_Z = 490;
    const sandBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            blocks: [
                { pos: [SAND_X, SAND_Y, SAND_Z], block: "minecraft:oak_fence" },
                { pos: [SAND_X + 1, SAND_Y, SAND_Z], block: "minecraft:sand" }
            ]
        }
    });
    console.log(textOf(sandBuild));
    check("mc_build sand+fence not an error", !sandBuild.isError);

    const sandAtOriginal = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [SAND_X + 1, SAND_Y, SAND_Z], to: [SAND_X + 1, SAND_Y, SAND_Z] }
    });
    const sandAtOriginalText = textOf(sandAtOriginal);
    console.log(sandAtOriginalText);
    check("sand is still at its placed position after the connection pass", /minecraft:sand/.test(sandAtOriginalText));

    const sandBelowOriginal = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [SAND_X + 1, SAND_Y - 1, SAND_Z], to: [SAND_X + 1, SAND_Y - 1, SAND_Z] }
    });
    const sandBelowOriginalText = textOf(sandBelowOriginal);
    console.log(sandBelowOriginalText);
    check("no sand fell to the block below", !/minecraft:sand/.test(sandBelowOriginalText));

    // --- mc_command ---------------------------------------------------------
    section("mc_command");
    const cmdResult = await client.callTool({ name: "mc_command", arguments: { command: "time query day" } });
    const cmdText = textOf(cmdResult);
    console.log(cmdText);
    check("mc_command not an error", !cmdResult.isError);
    check('mc_command output contains "time"', /time/i.test(cmdText));

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
