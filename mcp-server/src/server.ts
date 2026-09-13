// SPDX-License-Identifier: AGPL-3.0-or-later

import { McpServer } from "@modelcontextprotocol/server";

import type { PluginClient } from "./plugin-client.js";
import { registerAllTools } from "./tools/index.js";

const SERVER_NAME = "mc-ai-builder-mcp";
const SERVER_VERSION = "0.1.0";

/**
 * Server-level instructions. Clients show these to the model as soon as the
 * connector is enabled, even when they load tool definitions lazily (Claude
 * Desktop's "Load tools when needed"), so the model knows every tool that
 * exists and can search for it by exact name.
 */
const INSTRUCTIONS = `MC AI Builder: tools for reading and building in a live Minecraft (Paper) server. Nine tools are available - load them by name when needed:

mc_status   - server/plugin health and queue length
mc_players  - online players with position and facing ("here", "in front of me", "at my feet")
mc_survey   - surface heightmap of an x/z area as an ASCII relief map with numbers
mc_render   - PNG image of a region: top view, north/south/east/west facades, or a slice
mc_build    - place blocks in bulk (cuboid fills with modes replace/keep/outline/hollow/walls, plus individual blocks and sign text); the only tool that builds
mc_inspect  - exact block contents of a region (statistics, ASCII slice, sign text)
mc_snapshot - save a region before changing it (or list saved snapshots)
mc_restore  - roll a region back to a snapshot
mc_command  - run a server console command and return its output (escape hatch)

Typical flow: mc_players (if the request is relative to a player) -> mc_survey or mc_render to see the site -> mc_snapshot -> mc_build -> mc_render or mc_inspect to verify -> mc_restore if it went wrong.
Coordinates: X east, Z south, Y up; from/to corners are inclusive.`;

/**
 * Builds a fresh `McpServer` instance with every mc_* tool registered
 * against the shared, module-scoped `client`. Called once per stdio
 * connection and once per HTTP request (spec/plan section 4.0: "long-lived
 * state must live in module scope, not inside this factory").
 */
export function buildServer(client: PluginClient): McpServer {
    const server = new McpServer({ name: SERVER_NAME, version: SERVER_VERSION }, { instructions: INSTRUCTIONS });
    registerAllTools(server, client);
    return server;
}
