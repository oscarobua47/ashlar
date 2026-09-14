// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Thrown for any failure that originates from the plugin side of the
 * connection: an `{"ok":false}` RPC response, a connection/auth failure, or
 * a client-side timeout waiting for a response. `code` mirrors the plugin's
 * `ErrorCode` enum (spec section 3.2) for the RPC-level cases, plus two
 * client-only codes: `UNAVAILABLE` (no connection to the plugin) and
 * `TIMEOUT` (request sent but no response within the deadline).
 */
export class PluginError extends Error {
    readonly code: string;

    constructor(code: string, message: string) {
        super(message);
        this.name = "PluginError";
        this.code = code;
    }
}

/**
 * Turns a {@link PluginError} into text a model can act on: the plugin's
 * original message plus a concrete next step, per spec section 4.4 / plan
 * section 4.4. Unknown codes fall back to "<code>: <message>" unchanged.
 */
export function formatPluginError(error: PluginError, pluginUrl: string): string {
    const msg = error.message;
    switch (error.code) {
        case "VOLUME_EXCEEDED":
            return `${msg} This exceeds the per-call limit. Split the work into several mc_build calls (each <= 500,000 blocks and <= 1,024 chunks), or reduce the area for mc_survey/mc_inspect/mc_snapshot. For mc_render, use view "top" or "heightmap" (area-priced, any y range), or shrink the y range to the surface band from mc_survey (min-2 .. max+2).`;
        case "INVALID_BLOCK":
            return `${msg} This is not a valid block state. Use the "minecraft:" namespace and check property names, e.g. "minecraft:oak_stairs[facing=north]". Property values must match the block's actual state names.`;
        case "WORLD_NOT_ALLOWED":
            return `${msg} Ask the server owner to add this world to "world.allowed-worlds" in the plugin's config.yml, or omit "world" to use the plugin's default world.`;
        case "OUT_OF_BUILD_REGION":
            return `${msg} Choose coordinates inside the range given above, or ask the server owner to widen "world.build-region" in the plugin's config.yml.`;
        case "QUEUE_FULL":
            return `${msg} The plugin's operation queue is full. Wait a few seconds and retry; call mc_status and check "queuedOperations" before retrying.`;
        case "DISABLED":
            return `${msg} This feature is disabled in the plugin's config.yml on this server; ask the server owner to enable it if you need it.`;
        case "UNAUTHORIZED":
            return `The plugin rejected the MCP server's connection token: ${msg} Check that MC_PLUGIN_TOKEN matches "server.token" in the plugin's config.yml.`;
        case "UNAVAILABLE":
            return `MCP server cannot reach the plugin at ${pluginUrl}: ${msg} Check MC_PLUGIN_URL / MC_PLUGIN_TOKEN and that the plugin is running and its port is reachable.`;
        case "TIMEOUT":
            return `${msg} The plugin may be overloaded or the operation may be very large; check mc_status, then retry or split the request.`;
        case "BAD_REQUEST":
            if (/y range \[/.test(msg)) {
                // A y far outside the world is almost always a z coordinate in the y slot.
                return `${msg} Corners are [x, y, z] with y = height (typically 60-100 at the surface); it looks like a z value was placed in the y position. For a facade use from [x1, yBottom, z1] to [x2, yTop, z2]; for top/heightmap views you can pass [x, z] only.`;
            }
            return `${error.code}: ${msg}`;
        default:
            return `${error.code}: ${msg}`;
    }
}
