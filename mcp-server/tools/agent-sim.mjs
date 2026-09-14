#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Zero-dependency script to exercise the agent's tool-driving loop without a
// player online or a real model API: builds a PluginClient (no
// subscription) and a tool bridge against a running plugin test server, then
// calls runRequest() directly with a synthetic player object. Reads the same
// MC_PLUGIN_URL/MC_PLUGIN_TOKEN and AI_* environment variables as
// `ashlar-mcp --agent`.
//
// Usage:
//   node tools/agent-sim.mjs "<request>" [--pos x,y,z] [--facing south] [--name Tester] [--world world]

import { loadAgentConfig } from "../dist/agent/config.js";
import { runRequest } from "../dist/agent/runner.js";
import { createToolBridge } from "../dist/agent/tools.js";
import { loadPluginConnectionConfig } from "../dist/config.js";
import { PluginClient } from "../dist/plugin-client.js";

const args = process.argv.slice(2);
const requestText = args[0];
if (!requestText || requestText.startsWith("--")) {
    console.error('usage: node tools/agent-sim.mjs "<request>" [--pos x,y,z] [--facing south] [--name Tester] [--world world]');
    process.exit(1);
}

const rest = args.slice(1);
function opt(name, fallback) {
    const i = rest.indexOf(name);
    return i >= 0 && rest[i + 1] !== undefined ? rest[i + 1] : fallback;
}

const pos = opt("--pos", "0,64,0").split(",").map(Number);
const facing = opt("--facing", "south");
const name = opt("--name", "Tester");
const world = opt("--world", "world");

const FACING_OFFSETS = {
    north: [0, 0, -1],
    south: [0, 0, 1],
    east: [1, 0, 0],
    west: [-1, 0, 0]
};
const offset = FACING_OFFSETS[facing] ?? FACING_OFFSETS.south;
const inFront = [pos[0] + offset[0], pos[1] + offset[1], pos[2] + offset[2]];

const player = {
    name,
    uuid: "00000000-0000-0000-0000-0000000000a9",
    world,
    pos,
    facing,
    inFront,
    gameMode: "CREATIVE"
};

// No history persistence needed for a one-shot sim run.
const noopHistory = { get: () => [], append: () => {} };

async function main() {
    const pluginConfig = loadPluginConnectionConfig();
    const agentConfig = loadAgentConfig();

    const client = new PluginClient({
        url: pluginConfig.pluginUrl,
        token: pluginConfig.pluginToken,
        defaultTimeoutMs: pluginConfig.requestTimeoutMs
    });
    client.start();

    const bridge = await createToolBridge(client, { allowCommand: agentConfig.allowCommand });
    const controller = new AbortController();

    try {
        const reply = await runRequest({
            cfg: agentConfig,
            bridge,
            history: noopHistory,
            player,
            text: requestText,
            signal: controller.signal,
            onProgress: line => console.log(line)
        });
        console.log(reply);
    } finally {
        bridge.close();
        client.close();
    }
}

main().catch(err => {
    console.error(`agent-sim: fatal error: ${err.stack ?? err}`);
    process.exit(1);
});
