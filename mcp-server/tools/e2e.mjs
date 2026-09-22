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
// machine commonly already has another ashlar-mcp --http instance
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

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
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

    const client = new Client({ name: "ashlar-e2e", version: "0.1.0" });
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

    // Shared output directory for every PNG this script saves (mc_survey's default image, the
    // heightmap mc_render call, and the top/south/slice mc_render calls further below).
    const outDir = path.join(__dirname, "out");
    fs.mkdirSync(outDir, { recursive: true });

    // --- mc_survey over the same area, default format (image + text) --------
    section("mc_survey (same area, default format: image + text)");
    const surveyResult = await client.callTool({
        name: "mc_survey",
        arguments: { from: [BASE_X, BASE_Z], to: [BASE_X + 59, BASE_Z + 59] }
    });
    const surveyText = textOf(surveyResult);
    console.log(surveyText);
    check("mc_survey not an error", !surveyResult.isError);
    check("mc_survey output contains a legend", /Legend:/.test(surveyText));
    check("mc_survey output contains largest flat zone line", /Largest flat zone/.test(surveyText));
    const surveyImageBlock = surveyResult.content.find(c => c.type === "image");
    check("mc_survey (default) returned an image content block", !!surveyImageBlock);
    if (surveyImageBlock) {
        const surveyPngBytes = Buffer.from(surveyImageBlock.data, "base64");
        fs.writeFileSync(path.join(outDir, "heightmap.png"), surveyPngBytes);
        check(
            "mc_survey PNG starts with the PNG magic bytes",
            surveyPngBytes.length > 8 &&
                surveyPngBytes[0] === 0x89 &&
                surveyPngBytes[1] === 0x50 &&
                surveyPngBytes[2] === 0x4e &&
                surveyPngBytes[3] === 0x47
        );
    } else {
        failures++;
    }

    // --- mc_survey format:"text" (original ASCII relief map) ----------------
    section('mc_survey (format: "text")');
    const surveyTextFormatResult = await client.callTool({
        name: "mc_survey",
        arguments: { from: [BASE_X, BASE_Z], to: [BASE_X + 59, BASE_Z + 59], format: "text" }
    });
    const surveyTextFormatText = textOf(surveyTextFormatResult);
    console.log(surveyTextFormatText);
    check("mc_survey (format: text) not an error", !surveyTextFormatResult.isError);
    check("mc_survey (format: text) returned no image content block", !surveyTextFormatResult.content.some(c => c.type === "image"));
    check("mc_survey (format: text) output contains the ASCII map legend", /Legend:/.test(surveyTextFormatText));
    check("mc_survey (format: text) output contains largest flat zone line", /Largest flat zone/.test(surveyTextFormatText));
    check(
        "mc_survey (format: text) output contains an ASCII relief map (bucket/liquid characters after the ruler)",
        /[.,:\-=+*#~]{5,}/.test(surveyTextFormatText)
    );

    // --- mc_render view:"heightmap" on a 200x200 area (area-priced) ---------
    section('mc_render (view: "heightmap", 200x200 area)');
    const heightmapRenderStartedAt = Date.now();
    const heightmapRender = await client.callTool({
        name: "mc_render",
        arguments: { from: [BASE_X - 50, 64, BASE_Z - 50], to: [BASE_X + 149, 64, BASE_Z + 149], view: "heightmap" }
    });
    const heightmapRenderElapsedMs = Date.now() - heightmapRenderStartedAt;
    const heightmapRenderText = textOf(heightmapRender);
    console.log(heightmapRenderText);
    console.log(`  elapsed: ${heightmapRenderElapsedMs} ms`);
    check("mc_render heightmap not an error", !heightmapRender.isError);
    check("mc_render heightmap elapsed reported (>= 0 ms)", heightmapRenderElapsedMs >= 0);
    const heightmapImageBlock = heightmapRender.content.find(c => c.type === "image");
    check("mc_render heightmap returned an image content block", !!heightmapImageBlock);
    if (heightmapImageBlock) {
        const heightmapBytes = Buffer.from(heightmapImageBlock.data, "base64");
        fs.writeFileSync(path.join(outDir, "heightmap-render-200x200.png"), heightmapBytes);
        console.log(`  bytes: ${heightmapBytes.length}`);
        check(`mc_render heightmap PNG bytes < 3MB, got ${heightmapBytes.length}`, heightmapBytes.length < 3 * 1024 * 1024);
    } else {
        failures++;
    }
    check("mc_render heightmap text mentions View: heightmap", /View: heightmap/.test(heightmapRenderText));
    check("mc_render heightmap text contains largest flat zone line", /Largest flat zone/.test(heightmapRenderText));

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

    // --- mc_inspect: format:"columns" over the tower's hollow interior ------
    // Row z=BASE_Z+29 (strictly between the box's z borders 25/34) restricted to y=[66,69]
    // (strictly between the box's bottom/top caps 65/70): x=BASE_X+25 is the box's west wall
    // (part of the hollow ring, solid stone_bricks top to bottom), x=BASE_X+26..29 are interior
    // (hollowed out, all air).
    section('mc_inspect (format: "columns", tower hollow interior)');
    const columnsResult = await client.callTool({
        name: "mc_inspect",
        arguments: {
            from: [BASE_X + 25, 66, BASE_Z + 29],
            to: [BASE_X + 29, 69, BASE_Z + 29],
            format: "columns"
        }
    });
    const columnsText = textOf(columnsResult);
    console.log(columnsText);
    check("mc_inspect (columns) not an error", !columnsResult.isError);
    check(
        "mc_inspect (columns) header reports 5 columns and the runs-bottom-to-top wording",
        columnsText.includes(
            `x=[${BASE_X + 25}..${BASE_X + 29}] y=[66..69] z=[${BASE_Z + 29}..${BASE_Z + 29}] (5x4x1 = 20 blocks), 5 columns, runs bottom to top (ids without the minecraft: prefix):`
        )
    );
    check(
        "mc_inspect (columns) west-wall column is one solid stone_bricks run (the hollow ring)",
        columnsText.includes(`${BASE_X + 25},${BASE_Z + 29}: 66-69 stone_bricks`)
    );
    for (let x = BASE_X + 26; x <= BASE_X + 29; x++) {
        check(`mc_inspect (columns) interior column x=${x} is one all-air run`, columnsText.includes(`${x},${BASE_Z + 29}: 66-69 air`));
    }

    // --- mc_inspect: format:"columns" errors ---------------------------------
    section('mc_inspect (error: slice and format:"columns" are exclusive)');
    const columnsAndSlice = await client.callTool({
        name: "mc_inspect",
        arguments: {
            from: [BASE_X + 25, 66, BASE_Z + 25],
            to: [BASE_X + 34, 69, BASE_Z + 34],
            format: "columns",
            slice: { axis: "y", at: 66 }
        }
    });
    console.log(textOf(columnsAndSlice));
    check('slice + format:"columns" call isError', columnsAndSlice.isError === true);
    check(
        'slice + format:"columns" message names the exclusivity',
        /`slice` and `format: "columns"` are exclusive/.test(textOf(columnsAndSlice))
    );

    section('mc_inspect (error: format:"columns" over the 1024-column cap)');
    const tooManyColumns = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [0, 0, 0], to: [39, 0, 29], format: "columns" }
    });
    console.log(textOf(tooManyColumns));
    check('format:"columns" cap-exceeded call isError', tooManyColumns.isError === true);
    check(
        'format:"columns" cap-exceeded message names the 1024-column limit',
        /1200 columns, exceeding the 1024-column limit/.test(textOf(tooManyColumns))
    );

    // --- mc_restore -----------------------------------------------------------
    section("mc_restore");
    if (snapshotId) {
        const restoreResult = await client.callTool({ name: "mc_restore", arguments: { id: snapshotId } });
        const restoreText = textOf(restoreResult);
        console.log(restoreText);
        check("mc_restore not an error", !restoreResult.isError);
        check("mc_restore restored > 0 blocks", /Restored snapshot .*: [1-9]/.test(restoreText));
        check("mc_restore produced no support warnings", !/WARNINGS/.test(restoreText));

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
                    sign: { front: ["Made by", "Ashlar"] }
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
    check("Signs: line contains the front text", /Made by.*Ashlar/.test(signInspectText));

    // --- step4d Fix 2 regression: sand next to a fence must still not fall -----
    // The chunk must actually tick for this to prove anything: a scheduled sand fall only
    // runs in a ticking chunk (a player nearby, or the executor's post-task ticket hold),
    // which is how the original version of this check passed for months while the
    // fence's own refresh was knocking the sand down whenever a player stood nearby.
    section("mc_build (regression: unsupported sand next to a fence in the same batch, ticking chunk)");
    const SAND_X = 490;
    const SAND_Y = 90;
    const SAND_Z = 490;
    const forceload = await client.callTool({
        name: "mc_command",
        arguments: { command: `forceload add ${SAND_X} ${SAND_Z} ${SAND_X + 8} ${SAND_Z}` }
    });
    check("forceload add not an error", !forceload.isError);
    // Clear the strip first so every block below counts as freshly written even on a reused world.
    await client.callTool({
        name: "mc_build",
        arguments: { fills: [{ from: [SAND_X - 1, SAND_Y - 1, SAND_Z], to: [SAND_X + 7, SAND_Y, SAND_Z], block: "minecraft:air" }] }
    });
    const sandBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            fills: [
                // Supported case for the second half of this check: a stone base under that sand only.
                { from: [SAND_X + 6, SAND_Y - 1, SAND_Z], to: [SAND_X + 6, SAND_Y - 1, SAND_Z], block: "minecraft:stone" }
            ],
            blocks: [
                { pos: [SAND_X, SAND_Y, SAND_Z], block: "minecraft:oak_fence" },
                { pos: [SAND_X + 1, SAND_Y, SAND_Z], block: "minecraft:sand" },
                // Two fences next to supported sand: the refresh must still run there, so they connect to each other.
                { pos: [SAND_X + 4, SAND_Y, SAND_Z], block: "minecraft:oak_fence" },
                { pos: [SAND_X + 5, SAND_Y, SAND_Z], block: "minecraft:oak_fence" },
                { pos: [SAND_X + 6, SAND_Y, SAND_Z], block: "minecraft:sand" }
            ]
        }
    });
    console.log(textOf(sandBuild));
    check("mc_build sand+fence not an error", !sandBuild.isError);
    check("unsupported sand is reported as a support warning", /WARNINGS[\s\S]*minecraft:sand[\s\S]*nothing solid below/.test(textOf(sandBuild)));
    await sleep(1500); // a scheduled sand fall runs 2 ticks after the update; give it plenty.

    const sandAtOriginal = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [SAND_X + 1, SAND_Y, SAND_Z], to: [SAND_X + 1, SAND_Y, SAND_Z] }
    });
    const sandAtOriginalText = textOf(sandAtOriginal);
    console.log(sandAtOriginalText);
    check("sand is still at its placed position after the connection pass", /minecraft:sand/.test(sandAtOriginalText));

    const fenceBySupportedSand = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [SAND_X + 4, SAND_Y, SAND_Z], to: [SAND_X + 6, SAND_Y, SAND_Z] }
    });
    const fenceBySupportedSandText = textOf(fenceBySupportedSand);
    console.log(fenceBySupportedSandText);
    check("fences next to SUPPORTED sand are still refreshed (they connect to each other)",
        /oak_fence\[east=true/.test(fenceBySupportedSandText) && /oak_fence\[east=false,[a-z=,]*west=true\]/.test(fenceBySupportedSandText));
    check("supported sand stays too", /minecraft:sand/.test(fenceBySupportedSandText));
    await client.callTool({ name: "mc_command", arguments: { command: "forceload remove all" } });

    const sandBelowOriginal = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [SAND_X + 1, SAND_Y - 1, SAND_Z], to: [SAND_X + 1, SAND_Y - 1, SAND_Z] }
    });
    const sandBelowOriginalText = textOf(sandBelowOriginal);
    console.log(sandBelowOriginalText);
    check("no sand fell to the block below", !/minecraft:sand/.test(sandBelowOriginalText));

    // --- step8e: chest pairing ------------------------------------------------
    section("mc_build (two adjacent north-facing chests pair; a third facing south and a trapped chest stay single)");
    const CHEST_X = 520;
    const CHEST_Y = 70;
    const CHEST_Z = 520;
    const chestBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            fills: [
                { from: [CHEST_X - 1, CHEST_Y - 1, CHEST_Z - 1], to: [CHEST_X + 3, CHEST_Y - 1, CHEST_Z + 3], block: "minecraft:stone" },
                { from: [CHEST_X - 1, CHEST_Y, CHEST_Z - 1], to: [CHEST_X + 3, CHEST_Y + 2, CHEST_Z + 3], block: "minecraft:air" }
            ],
            blocks: [
                // First pair: same facing, same material, adjacent - must pair (left/right).
                { pos: [CHEST_X, CHEST_Y, CHEST_Z], block: "minecraft:chest[facing=north]" },
                { pos: [CHEST_X + 1, CHEST_Y, CHEST_Z], block: "minecraft:chest[facing=north]" },
                // Adjacent to the pair but facing south: must stay single.
                { pos: [CHEST_X + 2, CHEST_Y, CHEST_Z], block: "minecraft:chest[facing=south]" },
                // Trapped chest next to a normal chest, same facing: different material, both stay single.
                { pos: [CHEST_X, CHEST_Y, CHEST_Z + 2], block: "minecraft:trapped_chest[facing=north]" },
                { pos: [CHEST_X + 1, CHEST_Y, CHEST_Z + 2], block: "minecraft:chest[facing=north]" }
            ]
        }
    });
    const chestBuildText = textOf(chestBuild);
    console.log(chestBuildText);
    check("mc_build chests not an error", !chestBuild.isError);
    check("response text contains chestsPaired", /chestsPaired/.test(chestBuildText));
    check("chestsPaired: 1", /chestsPaired: 1/.test(chestBuildText));

    section("mc_inspect (chest area: expect exactly one left + one right, the rest single)");
    const chestStats = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [CHEST_X, CHEST_Y, CHEST_Z], to: [CHEST_X + 2, CHEST_Y, CHEST_Z + 2] }
    });
    const chestStatsText = textOf(chestStats);
    console.log(chestStatsText);
    check("mc_inspect chest area not an error", !chestStats.isError);
    check(
        "exactly one type=left chest",
        (chestStatsText.match(/minecraft:chest\[[^\]]*type=left[^\]]*\]/g) ?? []).length === 1
    );
    check(
        "exactly one type=right chest",
        (chestStatsText.match(/minecraft:chest\[[^\]]*type=right[^\]]*\]/g) ?? []).length === 1
    );
    check(
        "the south-facing third chest stayed single",
        /minecraft:chest\[facing=south,type=single/.test(chestStatsText)
    );
    check(
        "the trapped chest stayed single",
        /minecraft:trapped_chest\[[^\]]*type=single[^\]]*\]/.test(chestStatsText)
    );
    check(
        "the normal chest next to the trapped chest stayed single (not paired across materials)",
        (chestStatsText.match(/(?<!trapped_)minecraft:chest\[facing=north,type=single/g) ?? []).length === 1
    );

    // --- step4h: post-build support warnings ---------------------------------
    section("mc_build (support warnings: unsupported ladder, embedded torch, correct wall torch, floating door)");
    const WARN_X = 500;
    const WARN_Y = 95;
    const WARN_Z = 500;
    const warnBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            fills: [
                // Clear a generous air pocket first: unmanaged natural terrain may otherwise leave a
                // stray block exactly where a test expects air (behind the unsupported ladder) or solid
                // ground where a test expects nothing (below the floating door).
                { from: [WARN_X - 1, WARN_Y - 2, WARN_Z - 1], to: [WARN_X + 11, WARN_Y + 4, WARN_Z + 11], block: "minecraft:air" },
                // A solid 3x3x3 stone cube: the torch placed dead center (below) will have a solid
                // block below it AND on all four horizontal sides - the "embedded" case.
                { from: [WARN_X + 2, WARN_Y, WARN_Z + 2], to: [WARN_X + 4, WARN_Y + 2, WARN_Z + 4], block: "minecraft:stone" },
                // A thin stone wall (1 block thick along x): the wall_torch placed beside it (below) is
                // correctly attached and should raise no warning.
                { from: [WARN_X + 6, WARN_Y, WARN_Z + 6], to: [WARN_X + 6, WARN_Y + 2, WARN_Z + 8], block: "minecraft:stone" }
            ],
            blocks: [
                // Ladder facing east with nothing at x-1 (cleared to air above): unsupported.
                { pos: [WARN_X, WARN_Y, WARN_Z], block: "minecraft:ladder[facing=east]" },
                // Overwrites the stone cube's center block: standing torch surrounded by solid stone.
                { pos: [WARN_X + 3, WARN_Y + 1, WARN_Z + 3], block: "minecraft:torch" },
                // In the air block beside the wall, facing away from it (wall is one block west): correct.
                { pos: [WARN_X + 7, WARN_Y + 1, WARN_Z + 7], block: "minecraft:wall_torch[facing=east]" },
                // Door lower half floating in the cleared air pocket, nothing below it: unsupported.
                { pos: [WARN_X + 9, WARN_Y, WARN_Z + 9], block: "minecraft:oak_door[half=lower,facing=north,hinge=left]" }
            ]
        }
    });
    const warnBuildText = textOf(warnBuild);
    console.log(warnBuildText);
    check("mc_build (support warnings) not an error", !warnBuild.isError);
    check("WARNINGS header present", /WARNINGS \(blocks that would fall or pop off/.test(warnBuildText));
    check(
        "unsupported ladder warning present (facing=east, no support behind)",
        /minecraft:ladder\[[^\]]*facing=east[^\]]*\][^\n]*no support behind \(facing=east needs a solid block at x-1\)/.test(
            warnBuildText
        )
    );
    check("embedded torch warning present", /minecraft:torch[^\n]*embedded/.test(warnBuildText));
    check("floating door warning present", /minecraft:oak_door\[[^\]]*\][^\n]*nothing solid below/.test(warnBuildText));
    check(
        "correctly placed wall_torch raises no warning of its own (only mentioned inside the embedded torch's advice text)",
        !/\dx minecraft:wall_torch/.test(warnBuildText)
    );
    const warnLines = warnBuildText
        .split("\n")
        .filter(line => /^\s{2}\d+x /.test(line));
    check(`exactly 3 warning lines (got ${warnLines.length})`, warnLines.length === 3);

    section("regression: step4.7/4.8 scene (platform/tower, walls, panes, sign) has no false support warnings");
    // Reuses this same script's earlier builds (platform + hollow tower + stairs, "walls" mode,
    // connected pane row, oak_wall_sign) rather than rebuilding the scene - their text output was
    // already captured above, so this just asserts none of them carry a WARNINGS section.
    check("platform/tower build produced no support warnings", !/WARNINGS/.test(buildText));
    check("walls build produced no support warnings", !/WARNINGS/.test(wallsBuildText));
    check("pane row build produced no support warnings", !/WARNINGS/.test(textOf(paneBuild)));
    check("sign build produced no support warnings", !/WARNINGS/.test(textOf(signBuild)));
    // (mc_restore's own no-warnings check runs inline in the "mc_restore" section above.)

    // --- step4i: neighbour support checks after clears -----------------------
    // Real case (docs/prompts/step4i-prompt.md): an AI placed a ladder against a wall, then carved
    // a doorway through that wall in a later mc_build. The doorway fill writes air, which needs no
    // support itself, so the old SupportCheck (only examining blocks the task itself wrote) said
    // nothing - the ladder was left hanging. This section reproduces exactly that.
    section("mc_build (wall + ladder run against it, facing away from the wall)");
    const LADDER_X = 560;
    const LADDER_Y = 70;
    const LADDER_Z = 560;
    const ladderWallBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            fills: [
                // Generous air pocket first: unmanaged natural terrain may otherwise leave stray
                // blocks exactly where a later assertion expects air or nothing.
                { from: [LADDER_X - 2, LADDER_Y - 3, LADDER_Z - 2], to: [LADDER_X + 8, LADDER_Y + 8, LADDER_Z + 3], block: "minecraft:air" },
                // A floor under the ladder's bottom rung, so the "floating ladder bottom" rule does
                // not also fire - this test is specifically about the wall-behind-it rule.
                { from: [LADDER_X - 1, LADDER_Y - 1, LADDER_Z + 1], to: [LADDER_X + 7, LADDER_Y - 1, LADDER_Z + 1], block: "minecraft:stone" },
                // A 7-wide, 6-tall, 1-thick stone wall at z=LADDER_Z.
                { from: [LADDER_X, LADDER_Y, LADDER_Z], to: [LADDER_X + 6, LADDER_Y + 5, LADDER_Z], block: "minecraft:stone" }
            ],
            blocks: Array.from({ length: 6 }, (_, i) => ({
                // A 6-tall ladder run one block in front of the wall (z=LADDER_Z+1), facing south -
                // away from the wall behind it, at z=LADDER_Z.
                pos: [LADDER_X + 3, LADDER_Y + i, LADDER_Z + 1],
                block: "minecraft:ladder[facing=south]"
            }))
        }
    });
    const ladderWallBuildText = textOf(ladderWallBuild);
    console.log(ladderWallBuildText);
    check("ladder+wall build not an error", !ladderWallBuild.isError);
    check("ladder+wall build raises no warnings (fully supported)", !/WARNINGS/.test(ladderWallBuildText));

    section("mc_build (carve a 1x3x1 doorway through the wall, behind the middle of the ladder run)");
    const doorwayBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            fills: [{ from: [LADDER_X + 3, 72, LADDER_Z], to: [LADDER_X + 3, 74, LADDER_Z], block: "minecraft:air" }]
        }
    });
    const doorwayBuildText = textOf(doorwayBuild);
    console.log(doorwayBuildText);
    check("doorway carve not an error", !doorwayBuild.isError);
    check("doorway carve raises the WARNINGS header", /WARNINGS/.test(doorwayBuildText));
    check(
        "doorway carve warns about exactly the 3 ladder blocks that lost their wall, grouped into one range line",
        new RegExp(
            `3x minecraft:ladder\\[[^\\]]*facing=south[^\\]]*\\] at x=${LADDER_X + 3} y=72\\.\\.74 z=${LADDER_Z + 1}: ` +
                "no support behind \\(facing=south needs a solid block at z-1\\)"
        ).test(doorwayBuildText)
    );
    const doorwayWarnLines = doorwayBuildText.split("\n").filter(line => /^\s{2}\d+x /.test(line));
    check(`doorway carve produced exactly 1 warning line, i.e. nothing else (got ${doorwayWarnLines.length})`, doorwayWarnLines.length === 1);

    section("mc_build (20x10x20 stone volume with a wall_torch mounted on its boundary wall)");
    const VOL_X = 600;
    const VOL_Y = 100;
    const VOL_Z = 600;
    const volumeBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            fills: [
                { from: [VOL_X - 2, VOL_Y - 2, VOL_Z - 2], to: [VOL_X + 21, VOL_Y + 11, VOL_Z + 21], block: "minecraft:air" },
                { from: [VOL_X, VOL_Y, VOL_Z], to: [VOL_X + 19, VOL_Y + 9, VOL_Z + 19], block: "minecraft:stone" }
            ],
            // One block west of the volume, attached to the volume's west face (facing=west points
            // away from that face, back toward the torch's own position - i.e. away from the wall).
            blocks: [{ pos: [VOL_X - 1, VOL_Y + 5, VOL_Z + 5], block: "minecraft:wall_torch[facing=west]" }]
        }
    });
    const volumeBuildText = textOf(volumeBuild);
    console.log(volumeBuildText);
    check("volume+torch build not an error", !volumeBuild.isError);
    check("volume+torch build raises no warnings (torch correctly attached)", !/WARNINGS/.test(volumeBuildText));

    section("mc_build (clear the 20x10x20 volume next to the wall_torch)");
    const clearStartedAt = Date.now();
    const clearBuild = await client.callTool({
        name: "mc_build",
        arguments: { fills: [{ from: [VOL_X, VOL_Y, VOL_Z], to: [VOL_X + 19, VOL_Y + 9, VOL_Z + 19], block: "minecraft:air" }] }
    });
    const clearElapsedMs = Date.now() - clearStartedAt;
    const clearBuildText = textOf(clearBuild);
    console.log(clearBuildText);
    console.log(`  client-measured round trip: ${clearElapsedMs} ms`);
    check("volume clear not an error", !clearBuild.isError);
    check("volume clear raises the WARNINGS header", /WARNINGS/.test(clearBuildText));
    check(
        "volume clear warns about the wall_torch that lost its wall",
        new RegExp(
            `1x minecraft:wall_torch\\[[^\\]]*facing=west[^\\]]*\\] at ${VOL_X - 1},${VOL_Y + 5},${VOL_Z + 5}: ` +
                "no support behind \\(facing=west needs a solid block at x\\+1\\)"
        ).test(clearBuildText)
    );
    const clearWarnLines = clearBuildText.split("\n").filter(line => /^\s{2}\d+x /.test(line));
    check(`volume clear produced exactly 1 warning line, i.e. nothing else (got ${clearWarnLines.length})`, clearWarnLines.length === 1);
    const clearElapsedMatch = clearBuildText.match(/total: \d+\/\d+ changed in (\d+)ms/);
    const clearServerElapsedMs = clearElapsedMatch ? Number(clearElapsedMatch[1]) : null;
    console.log(`  server-reported elapsedMs: ${clearServerElapsedMs}`);
    check(
        `volume clear elapsedMs is small (< 5000ms, got ${clearServerElapsedMs})`,
        clearServerElapsedMs !== null && clearServerElapsedMs < 5000
    );

    // --- step8d: liquids: "flow" (physics-enabled water/lava) ---------------------------
    section('mc_build (liquids: "flow" - a water source spreads and falls like a fountain)');
    const LIQ_FLOW_X = 700;
    const LIQ_STATIC_X = 720;
    const LIQ_Z = 700;

    // No players are online on the test server, so a freshly-written chunk is loaded but stops
    // ticking as soon as the plugin's chunk ticket is gone - and fluid spread is scheduled block
    // ticks. The engine therefore keeps the tickets of a task that placed liquids with physics for
    // 10 s after it finishes (BuildTask#ticketHoldTicks); this section deliberately does NOT
    // /forceload anything, so it also proves that hold works.
    // Each test point is its own walled 7x7 pit (floor at y=60, walls y=60..64, open top) so a
    // spreading source cannot bleed into the other point's read window - one water source dropped
    // 3 blocks above the floor (y=63), at the pit's centre.
    function liquidPitOps(x0, z0) {
        return [
            { from: [x0 - 1, 58, z0 - 1], to: [x0 + 7, 67, z0 + 6], block: "minecraft:air" },
            { from: [x0, 60, z0], to: [x0 + 6, 60, z0 + 6], block: "minecraft:stone" },
            { from: [x0, 60, z0], to: [x0 + 6, 64, z0 + 6], block: "minecraft:stone", mode: "walls" }
        ];
    }

    const flowBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            fills: liquidPitOps(LIQ_FLOW_X, LIQ_Z),
            blocks: [{ pos: [LIQ_FLOW_X + 3, 63, LIQ_Z + 3], block: "minecraft:water" }],
            liquids: "flow"
        }
    });
    console.log(textOf(flowBuild));
    check('mc_build (liquids: "flow") not an error', !flowBuild.isError);

    section('mc_build (liquids: "static" control - same layout, default liquids: water stays a frozen source)');
    const staticBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            fills: liquidPitOps(LIQ_STATIC_X, LIQ_Z),
            blocks: [{ pos: [LIQ_STATIC_X + 3, 63, LIQ_Z + 3], block: "minecraft:water" }]
            // liquids omitted - defaults to "static".
        }
    });
    console.log(textOf(staticBuild));
    check('mc_build (liquids: "static") not an error', !staticBuild.isError);

    console.log("  waiting 3s (>40 ticks) for fluid physics to spread ...");
    await sleep(3000);

    const flowStats = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [LIQ_FLOW_X, 60, LIQ_Z], to: [LIQ_FLOW_X + 6, 63, LIQ_Z + 6] }
    });
    const flowStatsText = textOf(flowStats);
    console.log(flowStatsText);
    check("mc_inspect (flow pit) not an error", !flowStats.isError);
    check(
        'liquids:"flow" water spread beyond the single source (a non-source water level state is present)',
        /minecraft:water\[level=[1-8]\]/.test(flowStatsText)
    );

    const staticStats = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [LIQ_STATIC_X, 60, LIQ_Z], to: [LIQ_STATIC_X + 6, 63, LIQ_Z + 6] }
    });
    const staticStatsText = textOf(staticStats);
    console.log(staticStatsText);
    check("mc_inspect (static pit) not an error", !staticStats.isError);
    check(
        'liquids:"static" (default) water did not spread (no non-source water level state present)',
        !/minecraft:water\[level=[1-8]\]/.test(staticStatsText)
    );
    check(
        'liquids:"static" water is still exactly the one placed source block',
        /\s+1\s+[\d.]+%\s+minecraft:water\[level=0\]/.test(staticStatsText)
    );

    section('mc_build (error: liquids: "flow" cap exceeded)');
    const liquidCapExceeded = await client.callTool({
        name: "mc_build",
        arguments: {
            fills: [{ from: [750, 60, 700], to: [769, 79, 719], block: "minecraft:water" }],
            liquids: "flow"
        }
    });
    console.log(textOf(liquidCapExceeded));
    check('liquids:"flow" cap-exceeded call isError', liquidCapExceeded.isError === true);
    check(
        'liquids:"flow" cap-exceeded message mentions "flowing liquid blocks" and the 2000 cap',
        /flowing liquid blocks \d+ exceeds limit 2000/.test(textOf(liquidCapExceeded))
    );

    // --- step8k: mc_build `text` entries (lettering rendered by the plugin) -------------
    section('mc_build (text: "HI" facing south, scale 1) + mc_inspect columns through the "I"');
    const TEXT_X = 800;
    const TEXT_Y = 64;
    const TEXT_Z = 800;
    const textClear = await client.callTool({
        name: "mc_build",
        arguments: { fills: [{ from: [TEXT_X - 1, TEXT_Y - 1, TEXT_Z], to: [TEXT_X + 15, TEXT_Y + 8, TEXT_Z], block: "minecraft:air" }] }
    });
    check("text-check area clear not an error", !textClear.isError);

    const hiBuild = await client.callTool({
        name: "mc_build",
        arguments: {
            text: [{ text: "HI", pos: [TEXT_X, TEXT_Y, TEXT_Z], block: "minecraft:emerald_block", facing: "south", scale: 1 }]
        }
    });
    const hiBuildText = textOf(hiBuild);
    console.log(hiBuildText);
    check("mc_build (text HI) not an error", !hiBuild.isError);
    check('mc_build (text HI) response has a "Text:" section', /^Text:$/m.test(hiBuildText));
    check(
        'mc_build (text HI) reports the exact bounding box [800,64,800] -> [810,70,800] (H:5 + spacing:1 + I:5 = 11 wide, 7 tall)',
        hiBuildText.includes("[800,64,800] -> [810,70,800]")
    );

    // "I" is the 5x7 font's 2nd glyph in "HI": H occupies columns 0-4, a 1-column gap (default
    // spacing) is column 5, so "I" occupies columns 6-10 at world x = 800+6..800+10. Its glyph is
    // a full-width serif on row 0 (top) and row 6 (bottom) and a single-column stem (glyph-local
    // column 2) on every row in between - so glyph-local column 2 (world x = 800+6+2 = 808) is lit
    // on every one of the 7 rows: a solid, uninterrupted 7-block vertical run from y=64 (bottom,
    // row 6) to y=70 (top, row 0).
    const iColumn = await client.callTool({
        name: "mc_inspect",
        arguments: { from: [808, TEXT_Y - 2, TEXT_Z], to: [808, TEXT_Y + 9, TEXT_Z], format: "columns" }
    });
    const iColumnText = textOf(iColumn);
    console.log(iColumnText);
    check("mc_inspect (I column) not an error", !iColumn.isError);
    // mc_inspect "columns" format strips the "minecraft:" prefix from block ids.
    const iRunMatch = iColumnText.match(/(\d+)-(\d+) emerald_block/);
    check('column x=808 has exactly one emerald_block run (the "I" stem)', !!iRunMatch);
    if (iRunMatch) {
        const lo = Number(iRunMatch[1]);
        const hi = Number(iRunMatch[2]);
        check(`"I" stem run is exactly y=64..70 (got ${lo}..${hi})`, lo === 64 && hi === 70);
        check(`"I" stem run is exactly 7 blocks tall (got ${hi - lo + 1})`, hi - lo + 1 === 7);
    }

    section("mc_build (text: error cases - bad facing, scale out of range, empty text, text over 64 chars)");
    const textBadFacing = await client.callTool({
        name: "mc_build",
        arguments: { text: [{ text: "HI", pos: [TEXT_X, TEXT_Y, TEXT_Z], block: "minecraft:stone", facing: "sideways" }] }
    });
    check("text bad facing isError", textBadFacing.isError === true);

    const textScaleFive = await client.callTool({
        name: "mc_build",
        arguments: { text: [{ text: "HI", pos: [TEXT_X, TEXT_Y, TEXT_Z], block: "minecraft:stone", scale: 5 }] }
    });
    check("text scale 5 (out of 1-4 range) isError", textScaleFive.isError === true);

    const textEmpty = await client.callTool({
        name: "mc_build",
        arguments: { text: [{ text: "   ", pos: [TEXT_X, TEXT_Y, TEXT_Z], block: "minecraft:stone" }] }
    });
    check("text empty (blank after trim) isError", textEmpty.isError === true);

    const textTooLong = await client.callTool({
        name: "mc_build",
        arguments: { text: [{ text: "A".repeat(65), pos: [TEXT_X, TEXT_Y, TEXT_Z], block: "minecraft:stone" }] }
    });
    check("text over 64 characters isError", textTooLong.isError === true);

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
