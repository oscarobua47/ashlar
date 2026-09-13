// SPDX-License-Identifier: AGPL-3.0-or-later

import type { McpServer } from "@modelcontextprotocol/server";
import * as z from "zod/v4";

import type { PluginClient } from "../plugin-client.js";
import { runTool } from "./helpers.js";

const MAX_VOLUME = 200_000;

const inputSchema = z
    .object({
        action: z
            .enum(["create", "list"])
            .optional()
            .describe('"create" (default) takes a new snapshot; "list" returns every snapshot currently stored.'),
        world: z.string().min(1).optional().describe("World name for a create. Omitted uses the plugin's configured default world."),
        from: z
            .tuple([z.number().int(), z.number().int(), z.number().int()])
            .optional()
            .describe("Inclusive [x, y, z] corner of the region to capture. Required when action is create."),
        to: z
            .tuple([z.number().int(), z.number().int(), z.number().int()])
            .optional()
            .describe("Inclusive [x, y, z] corner opposite `from`. Required when action is create."),
        label: z.string().optional().describe("Optional free-text note stored with the snapshot, e.g. \"before tower\".")
    })
    .refine(v => (v.action ?? "create") !== "create" || (v.from && v.to), {
        message: '"from" and "to" are required when action is "create"'
    });

const DESCRIPTION = `Captures the current block contents of a region so it can be restored later with mc_restore, or lists every snapshot currently stored on the server. This is the manual counterpart to mc_build's own \`snapshot: true\` option - use it to protect an area before an experimental or risky change, independent of any single build call.

WHEN TO USE: before manually editing an area across several separate mc_build calls when you want one restore point covering all of them (mc_build's own snapshot option only covers that single call's bounding box); before letting a user try something destructive; to check what snapshots already exist and their ids/labels/timestamps before deciding whether to restore one, or before running low on the server's snapshot slots (oldest snapshots are evicted automatically once the configured maximum is reached).

WHEN NOT TO USE: to read current block contents (use mc_inspect - snapshots do not display their contents, they only exist to be restored later). If you already passed \`snapshot: true\` to mc_build for a single build call, calling mc_snapshot again for the same region is redundant.

PARAMETERS: \`action\` is "create" (default) or "list". For "create": \`world\` (optional, defaults to the plugin's default world), \`from\`/\`to\` (required, inclusive [x, y, z] corners in any order, X east/Z south/Y up, volume up to 200,000 blocks), and an optional \`label\` string to help identify the snapshot later. "list" takes no other parameters and returns every stored snapshot regardless of world.

SIDE EFFECTS: "create" does not modify the world - it only reads and stores a copy of the region, comparable in cost to mc_inspect over the same volume. It does consume one of the server's limited snapshot slots; the oldest snapshot is silently evicted once the configured maximum count is reached, so do not rely on a snapshot existing indefinitely. "list" has no side effects at all.`;

interface SnapshotResult {
    id: string;
    world: string;
    from: [number, number, number];
    to: [number, number, number];
    volume: number;
    createdAt: string;
    label?: string;
}

interface ListSnapshotsResult {
    snapshots: SnapshotResult[];
}

export function registerMcSnapshot(server: McpServer, client: PluginClient): void {
    server.registerTool(
        "mc_snapshot",
        {
            title: "Snapshot region",
            description: DESCRIPTION,
            inputSchema,
            annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false }
        },
        async ({ action, world, from, to, label }) => {
            return runTool(client, "mc_snapshot", async () => {
                if ((action ?? "create") === "list") {
                    const result = (await client.request("list_snapshots", {})) as ListSnapshotsResult;
                    if (result.snapshots.length === 0) {
                        return "No snapshots stored.";
                    }
                    const lines = ["id                          world   from              to                volume  createdAt             label"];
                    for (const s of result.snapshots) {
                        lines.push(
                            `${s.id.padEnd(28)}${s.world.padEnd(8)}${s.from.join(",").padEnd(18)}${s.to.join(",").padEnd(18)}${String(s.volume).padEnd(8)}${s.createdAt.padEnd(22)}${s.label ?? ""}`
                        );
                    }
                    return lines.join("\n");
                }

                if (!from || !to) {
                    throw new Error('"from" and "to" are required when action is "create"');
                }
                const [x1, y1, z1] = [Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2])];
                const [x2, y2, z2] = [Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2])];
                const volume = (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
                if (volume > MAX_VOLUME) {
                    throw new Error(
                        `mc_snapshot region volume ${volume} exceeds the 200,000-block limit. Reduce the from/to range or split it into several snapshots.`
                    );
                }

                const snap = (await client.request("snapshot", {
                    world,
                    from: [x1, y1, z1],
                    to: [x2, y2, z2],
                    label
                })) as SnapshotResult;
                return (
                    `Snapshot ${snap.id} created for world "${snap.world}", volume ${snap.volume}, at ${snap.createdAt}.\n` +
                    `Call mc_restore({"id":"${snap.id}"}) to restore this region later.`
                );
            });
        }
    );
}
