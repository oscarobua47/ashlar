// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.RpcHandler;
import net.rcwalter.ashlar.tool.ArgParse;
import net.rcwalter.ashlar.tool.Tool;
import net.rcwalter.ashlar.tool.ToolArgError;
import net.rcwalter.ashlar.tool.ToolResult;
import net.rcwalter.ashlar.tool.ToolRunner;
import net.rcwalter.ashlar.tool.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.rcwalter.ashlar.tool.mc.JsonUtil.intArray;
import static net.rcwalter.ashlar.tool.mc.JsonUtil.joinArray;
import static net.rcwalter.ashlar.tool.mc.JsonUtil.padEnd;

/** Pure-Java port of {@code mcp-server/src/tools/mc-snapshot.ts}. No text differences from the TS tool. */
public final class McSnapshot implements Tool {

    private static final long MAX_VOLUME = 200_000;
    private static final List<String> ACTIONS = List.of("create", "list");

    private final ToolSpec spec = ToolSpec.load("mc_snapshot");
    private final RpcHandler snapshotHandler;
    private final RpcHandler listSnapshotsHandler;

    public McSnapshot(RpcHandler snapshotHandler, RpcHandler listSnapshotsHandler) {
        this.snapshotHandler = snapshotHandler;
        this.listSnapshotsHandler = listSnapshotsHandler;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    /** Client-side pre-check mirroring mc-snapshot.ts's {@code MAX_VOLUME} guard, exposed for unit testing. */
    static void checkVolume(long volume) {
        if (volume > MAX_VOLUME) {
            throw new ToolArgError("mc_snapshot region volume " + volume
                    + " exceeds the 200,000-block limit. Reduce the from/to range or split it into several snapshots.");
        }
    }

    record Args(String action, String world, int[] from, int[] to, String label) {
        static Args parse(JsonObject o) {
            String action = ArgParse.optEnum(o, "action", ACTIONS, "create");
            String world = ArgParse.optString(o, "world");
            int[] from = ArgParse.has(o, "from") ? ArgParse.requireCoords3(o, "from") : null;
            int[] to = ArgParse.has(o, "to") ? ArgParse.requireCoords3(o, "to") : null;
            String label = ArgParse.optString(o, "label");
            if (action.equals("create") && (from == null || to == null)) {
                throw new ToolArgError("\"from\" and \"to\" are required when action is \"create\"");
            }
            return new Args(action, world, from, to, label);
        }
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_snapshot", () -> {
            Args a = Args.parse(args);

            if (a.action().equals("list")) {
                return listSnapshotsHandler.handle(ctx, new JsonObject()).thenApply(el -> {
                    JsonArray snaps = el.getAsJsonObject().getAsJsonArray("snapshots");
                    if (snaps.isEmpty()) {
                        return "No snapshots stored.";
                    }
                    List<String> lines = new ArrayList<>();
                    lines.add("id                          world   from              to                volume  createdAt             label");
                    for (JsonElement se : snaps) {
                        JsonObject s = se.getAsJsonObject();
                        lines.add(padEnd(s.get("id").getAsString(), 28)
                                + padEnd(s.get("world").getAsString(), 8)
                                + padEnd(joinArray(s.getAsJsonArray("from")), 18)
                                + padEnd(joinArray(s.getAsJsonArray("to")), 18)
                                + padEnd(String.valueOf(s.get("volume").getAsLong()), 8)
                                + padEnd(s.get("createdAt").getAsString(), 22)
                                + (s.has("label") && !s.get("label").isJsonNull() ? s.get("label").getAsString() : ""));
                    }
                    return String.join("\n", lines);
                });
            }

            if (a.from() == null || a.to() == null) {
                throw new ToolArgError("\"from\" and \"to\" are required when action is \"create\"");
            }
            int x1 = Math.min(a.from()[0], a.to()[0]), y1 = Math.min(a.from()[1], a.to()[1]), z1 = Math.min(a.from()[2], a.to()[2]);
            int x2 = Math.max(a.from()[0], a.to()[0]), y2 = Math.max(a.from()[1], a.to()[1]), z2 = Math.max(a.from()[2], a.to()[2]);
            long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
            checkVolume(volume);

            JsonObject params = new JsonObject();
            if (a.world() != null) {
                params.addProperty("world", a.world());
            }
            params.add("from", intArray(x1, y1, z1));
            params.add("to", intArray(x2, y2, z2));
            if (a.label() != null) {
                params.addProperty("label", a.label());
            }

            return snapshotHandler.handle(ctx, params).thenApply(el -> {
                JsonObject snap = el.getAsJsonObject();
                return "Snapshot " + snap.get("id").getAsString() + " created for world \"" + snap.get("world").getAsString()
                        + "\", volume " + snap.get("volume").getAsLong() + ", at " + snap.get("createdAt").getAsString() + ".\n"
                        + "Call mc_restore({\"id\":\"" + snap.get("id").getAsString() + "\"}) to restore this region later.";
            });
        });
    }
}
