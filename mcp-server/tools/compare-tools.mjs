#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Cross-implementation comparison (docs/private/prompts/step7c-prompt.md
// section 5): runs the same mc_* calls through the *current* Node tool
// implementations (in-memory MCP client over dist/server.js, exactly like
// src/agent/tools.ts) and through the plugin's new tool_call RPC, and
// compares the returned content.
//
// Read-only tools (mc_status minus its connection line, mc_players,
// mc_survey both formats, mc_render every view, mc_inspect stats + slice,
// mc_snapshot list) must be byte-identical: text exactly equal, images with
// identical dimensions and identical raw base64 bytes (the plugin renders
// both paths' images, so they are the same bytes by construction).
//
// Write tools (mc_build, mc_restore) run in two disjoint, identically-shaped
// regions - region A through the Node path, region B (offset along x only)
// through the RPC path - then compare texts after translating B's
// coordinates back onto A's and masking snapshot ids/timestamps/elapsed-ms.
//
// Prints a PASS/FAIL table and exits non-zero on any FAIL.
//
// Usage:
//   cd mcp-server && npm run build
//   MC_PLUGIN_URL=ws://127.0.0.1:8765 MC_PLUGIN_TOKEN=<token> node tools/compare-tools.mjs

import { Client, InMemoryTransport } from "@modelcontextprotocol/client";

import { buildServer } from "../dist/server.js";
import { PluginClient } from "../dist/plugin-client.js";

const MC_PLUGIN_URL = process.env.MC_PLUGIN_URL ?? "ws://127.0.0.1:8765";
const MC_PLUGIN_TOKEN = process.env.MC_PLUGIN_TOKEN ?? "24e77d575101fd68043ba698c67bf45d";

// Read-only test area: any loaded region works - both paths just read it,
// so whatever terrain is there is read identically by construction.
const SURVEY_FROM = [Number(process.env.CMP_SURVEY_X1 ?? 200), Number(process.env.CMP_SURVEY_Z1 ?? 200)];
const SURVEY_TO = [Number(process.env.CMP_SURVEY_X2 ?? 230), Number(process.env.CMP_SURVEY_Z2 ?? 230)];
const FACADE_Y1 = Number(process.env.CMP_FACADE_Y1 ?? 60);
const FACADE_Y2 = Number(process.env.CMP_FACADE_Y2 ?? 90);
const INSPECT_FROM = [Number(process.env.CMP_INSPECT_X1 ?? 200), Number(process.env.CMP_INSPECT_Y1 ?? 64), Number(process.env.CMP_INSPECT_Z1 ?? 200)];
const INSPECT_TO = [Number(process.env.CMP_INSPECT_X2 ?? 215), Number(process.env.CMP_INSPECT_Y2 ?? 80), Number(process.env.CMP_INSPECT_Z2 ?? 215)];

// Write-region test geometry: region A (Node) and region B (RPC), same
// shape, B offset along x only, high in the sky so pre-existing terrain is
// air on both sides (avoids a natural-terrain difference between the two
// regions masquerading as an implementation difference). A simple numeric
// substring replace of every x value used translates B's text back onto A's
// - safe because these coordinates do not collide with any other number
// (volume/changed counts, elapsed ms, ...) that appears in the output.
const REGION_A_X = Number(process.env.CMP_REGION_A_X ?? 300000);
const REGION_B_X = Number(process.env.CMP_REGION_B_X ?? 300100);
const REGION_Y = Number(process.env.CMP_REGION_Y ?? 200);
const REGION_Z = Number(process.env.CMP_REGION_Z ?? 300000);
const REGION_W = 5; // x width
const REGION_H = 4; // y height
const REGION_D = 4; // z depth
const OFFSET_X = REGION_B_X - REGION_A_X;

const rows = [];
let failures = 0;

function record(name, ok, detail) {
    rows.push({ name, ok, detail: detail ?? "" });
    if (!ok) failures++;
}

// ---------------------------------------------------------------------
// Node path: in-memory MCP client over dist/server.js (agent/tools.ts's own pattern).
// ---------------------------------------------------------------------
async function connectNode() {
    const pluginClient = new PluginClient({ url: MC_PLUGIN_URL, token: MC_PLUGIN_TOKEN, defaultTimeoutMs: 30_000 });
    pluginClient.start();
    await waitForConnected(pluginClient);

    const server = buildServer(pluginClient);
    const [serverTransport, clientTransport] = InMemoryTransport.createLinkedPair();
    const client = new Client({ name: "compare-tools-node", version: "0.0.0" });
    await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);

    return {
        async call(name, args) {
            const result = await client.callTool({ name, arguments: args });
            return normalizeContent(result.content, result.isError === true);
        },
        async close() {
            await client.close();
            await server.close();
            pluginClient.close();
        }
    };
}

function waitForConnected(pluginClient, timeoutMs = 15_000) {
    return new Promise((resolve, reject) => {
        const start = Date.now();
        const tick = () => {
            if (pluginClient.isConnected()) {
                resolve();
                return;
            }
            if (Date.now() - start > timeoutMs) {
                reject(new Error("timed out waiting for the Node plugin client to connect"));
                return;
            }
            setTimeout(tick, 200);
        };
        tick();
    });
}

// ---------------------------------------------------------------------
// RPC path: a minimal raw WebSocket client speaking tool_catalog/tool_call directly.
// ---------------------------------------------------------------------
function connectRpc() {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(MC_PLUGIN_URL);
        let nextId = 1;
        const pending = new Map();
        const authTimeout = setTimeout(() => reject(new Error("RPC auth timed out")), 15_000);

        function request(method, params) {
            return new Promise((res, rej) => {
                const id = `cmp-${nextId++}`;
                pending.set(id, { resolve: res, reject: rej });
                ws.send(JSON.stringify({ id, method, params }));
            });
        }

        const api = {
            request,
            async call(name, args) {
                const result = await request("tool_call", { name, args });
                return normalizeContent(result.content, result.isError === true);
            },
            close() {
                ws.close();
            }
        };

        ws.addEventListener("open", () => {
            ws.send(JSON.stringify({ id: "auth-1", method: "auth", params: { token: MC_PLUGIN_TOKEN } }));
        });
        ws.addEventListener("message", ev => {
            const msg = JSON.parse(String(ev.data));
            if (msg.event === "progress") return;
            if (msg.id === "auth-1") {
                clearTimeout(authTimeout);
                if (msg.ok) {
                    resolve(api);
                } else {
                    reject(new Error(`RPC auth failed: ${JSON.stringify(msg.error)}`));
                }
                return;
            }
            const id = String(msg.id);
            const entry = pending.get(id);
            if (!entry) return;
            pending.delete(id);
            if (msg.ok) {
                entry.resolve(msg.result);
            } else {
                entry.reject(new Error(`${msg.error?.code}: ${msg.error?.message}`));
            }
        });
        ws.addEventListener("error", err => reject(err instanceof Error ? err : new Error(String(err))));
    });
}

function normalizeContent(content, isError) {
    const texts = (content ?? []).filter(c => c.type === "text").map(c => c.text);
    const images = (content ?? []).filter(c => c.type === "image").map(c => ({ data: c.data, mimeType: c.mimeType }));
    return { texts, images, isError };
}

// ---------------------------------------------------------------------
// Comparison helpers
// ---------------------------------------------------------------------

function pngDims(base64) {
    const head = Buffer.from(base64.slice(0, 64), "base64");
    if (head.length < 24 || head.readUInt32BE(12) !== 0x49484452) return null;
    return { width: head.readUInt32BE(16), height: head.readUInt32BE(20) };
}

function imagesEqual(a, b) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
        if (a[i].mimeType !== b[i].mimeType || a[i].data !== b[i].data) return false;
        const da = pngDims(a[i].data);
        const db = pngDims(b[i].data);
        if (!da || !db || da.width !== db.width || da.height !== db.height) return false;
    }
    return true;
}

function diffSummary(a, b) {
    if (a === b) return "";
    const la = a.split("\n");
    const lb = b.split("\n");
    for (let i = 0; i < Math.max(la.length, lb.length); i++) {
        if (la[i] !== lb[i]) {
            return `line ${i + 1}: node=${JSON.stringify(la[i] ?? "<missing>")} rpc=${JSON.stringify(lb[i] ?? "<missing>")}`;
        }
    }
    return "(equal)";
}

const identity = t => t;

/**
 * Compares one read-only tool call across both paths. `args` is sent to both verbatim (no
 * translation needed for reads). `toolName` is the actual mc_* tool to call; `label` is only the
 * row name shown in the report (several rows call the same tool with different args, e.g.
 * mc_survey's two formats or mc_render's several views).
 */
async function compareReadOnly(label, toolName, node, rpc, args, { transformNode = identity, transformRpc = identity } = {}) {
    const [nodeResult, rpcResult] = await Promise.all([node.call(toolName, args), rpc.call(toolName, args)]);
    const nodeText = transformNode(nodeResult.texts.join("\n"));
    const rpcText = transformRpc(rpcResult.texts.join("\n"));
    const textOk = nodeText === rpcText;
    const imagesOk = imagesEqual(nodeResult.images, rpcResult.images);
    const noErrors = !nodeResult.isError && !rpcResult.isError;
    const ok = textOk && imagesOk && noErrors;
    let detail = "";
    if (!noErrors) detail = `isError: node=${nodeResult.isError} rpc=${rpcResult.isError}`;
    else if (!textOk) detail = diffSummary(nodeText, rpcText);
    else if (!imagesOk) detail = "image content differs";
    record(label, ok, detail);
}

function dropLastLine(text) {
    const lines = text.split("\n");
    lines.pop();
    return lines.join("\n");
}

/** Masks fields that legitimately vary run to run: snapshot ids, ISO timestamps, elapsed-ms counters. */
function mask(text) {
    return text
        .replace(/snap-\d{8}-\d{6}-[0-9a-f]{4}/g, "<snapshot-id>")
        .replace(/\d{4}-\d{2}-\d{2}T[\d:.]+Z/g, "<timestamp>")
        .replace(/ in \d+ms/g, " in <ms>ms")
        .replace(/, at <timestamp>/g, ", at <timestamp>");
}

/** Translates region B's text back onto region A's coordinates (x-only offset), then masks. */
function translateAndMask(text) {
    let out = text;
    // A little slack beyond the region's own width/depth: warnings can reference a neighbour
    // just outside the fill (e.g. the "nothing solid below" torch check looks one block down/out).
    for (let dx = -2; dx < REGION_W + 2; dx++) {
        const bVal = REGION_B_X + dx;
        const aVal = REGION_A_X + dx;
        out = out.split(String(bVal)).join(String(aVal));
    }
    return mask(out);
}

function offsetOps(ops, dx) {
    return ops.map(op => {
        const out = { ...op };
        if (out.from) out.from = [out.from[0] + dx, out.from[1], out.from[2]];
        if (out.to) out.to = [out.to[0] + dx, out.to[1], out.to[2]];
        if (out.pos) out.pos = [out.pos[0] + dx, out.pos[1], out.pos[2]];
        return out;
    });
}

async function testBuild(node, rpc) {
    const fillsA = [
        {
            from: [REGION_A_X, REGION_Y, REGION_Z],
            to: [REGION_A_X + REGION_W - 1, REGION_Y + REGION_H - 1, REGION_Z + REGION_D - 1],
            block: "minecraft:stone"
        }
    ];
    // Deliberate unsupported block: a standing torch two blocks above the filled box's top,
    // with air directly beneath it, to exercise the WARNINGS section on both paths.
    const blocksA = [{ pos: [REGION_A_X + 1, REGION_Y + REGION_H + 1, REGION_Z + 1], block: "minecraft:torch" }];

    const fillsB = offsetOps(fillsA, OFFSET_X);
    const blocksB = offsetOps(blocksA, OFFSET_X);

    const [nodeResult, rpcResult] = await Promise.all([
        node.call("mc_build", { fills: fillsA, blocks: blocksA, snapshot: true }),
        rpc.call("mc_build", { fills: fillsB, blocks: blocksB, snapshot: true })
    ]);

    const nodeRaw = nodeResult.texts.join("\n");
    const rpcRaw = rpcResult.texts.join("\n");
    const nodeText = mask(nodeRaw);
    const rpcText = translateAndMask(rpcRaw);
    const ok = nodeText === rpcText && !nodeResult.isError && !rpcResult.isError;
    record("mc_build", ok, ok ? "" : diffSummary(nodeText, rpcText));

    const nodeSnapId = nodeRaw.match(/Snapshot (snap-\S+) created/)?.[1];
    const rpcSnapId = rpcRaw.match(/Snapshot (snap-\S+) created/)?.[1];
    return { nodeSnapId, rpcSnapId };
}

async function testRestore(node, rpc, nodeSnapId, rpcSnapId) {
    if (!nodeSnapId || !rpcSnapId) {
        record("mc_restore", false, `missing snapshot id from mc_build (node=${nodeSnapId}, rpc=${rpcSnapId})`);
        return;
    }
    const [nodeResult, rpcResult] = await Promise.all([
        node.call("mc_restore", { id: nodeSnapId }),
        rpc.call("mc_restore", { id: rpcSnapId })
    ]);
    const nodeText = mask(nodeResult.texts.join("\n"));
    const rpcText = translateAndMask(rpcResult.texts.join("\n"));
    const ok = nodeText === rpcText && !nodeResult.isError && !rpcResult.isError;
    record("mc_restore", ok, ok ? "" : diffSummary(nodeText, rpcText));
}

function printTable() {
    console.log("\nTool                          Result");
    console.log("----                          ------");
    for (const r of rows) {
        console.log(`${r.name.padEnd(30)}${r.ok ? "PASS" : "FAIL"}${r.ok ? "" : "  " + r.detail}`);
    }
    console.log(`\n${rows.length - failures}/${rows.length} passed`);
}

async function main() {
    const node = await connectNode();
    const rpc = await connectRpc();

    await compareReadOnly("mc_status", "mc_status", node, rpc, {}, { transformNode: dropLastLine, transformRpc: dropLastLine });
    await compareReadOnly("mc_players", "mc_players", node, rpc, {});
    await compareReadOnly("mc_survey (image)", "mc_survey", node, rpc, { from: SURVEY_FROM, to: SURVEY_TO });
    await compareReadOnly("mc_survey (text)", "mc_survey", node, rpc, { from: SURVEY_FROM, to: SURVEY_TO, format: "text" });
    await compareReadOnly("mc_render (top)", "mc_render", node, rpc, { from: SURVEY_FROM, to: SURVEY_TO, view: "top" });
    await compareReadOnly("mc_render (heightmap)", "mc_render", node, rpc, { from: SURVEY_FROM, to: SURVEY_TO, view: "heightmap" });
    for (const view of ["north", "south", "east", "west"]) {
        await compareReadOnly(`mc_render (${view})`, "mc_render", node, rpc, {
            from: [SURVEY_FROM[0], FACADE_Y1, SURVEY_FROM[1]],
            to: [SURVEY_TO[0], FACADE_Y2, SURVEY_TO[1]],
            view
        });
    }
    await compareReadOnly("mc_render (slice)", "mc_render", node, rpc, {
        from: [SURVEY_FROM[0], FACADE_Y1, SURVEY_FROM[1]],
        to: [SURVEY_TO[0], FACADE_Y2, SURVEY_TO[1]],
        view: "slice",
        slice: { axis: "y", at: FACADE_Y1 }
    });
    await compareReadOnly("mc_inspect (stats)", "mc_inspect", node, rpc, { from: INSPECT_FROM, to: INSPECT_TO });
    await compareReadOnly("mc_inspect (slice)", "mc_inspect", node, rpc, {
        from: INSPECT_FROM,
        to: INSPECT_TO,
        slice: { axis: "y", at: INSPECT_FROM[1] }
    });
    await compareReadOnly("mc_snapshot (list)", "mc_snapshot", node, rpc, { action: "list" });

    const { nodeSnapId, rpcSnapId } = await testBuild(node, rpc);
    await testRestore(node, rpc, nodeSnapId, rpcSnapId);

    printTable();

    await node.close();
    rpc.close();
    process.exitCode = failures > 0 ? 1 : 0;
}

main().catch(err => {
    console.error(err);
    process.exitCode = 1;
});
