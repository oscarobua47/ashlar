// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.RpcHandler;
import net.rcwalter.ashlar.tool.Tool;
import net.rcwalter.ashlar.tool.ToolResult;
import net.rcwalter.ashlar.tool.ToolRunner;
import net.rcwalter.ashlar.tool.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Pure-Java port of {@code mcp-server/src/tools/mc-players.ts}. No text differences from the TS tool. */
public final class McPlayers implements Tool {

    private final ToolSpec spec = ToolSpec.load("mc_players");
    private final RpcHandler playersHandler;

    public McPlayers(RpcHandler playersHandler) {
        this.playersHandler = playersHandler;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_players", () -> playersHandler.handle(ctx, new JsonObject()).thenApply(el -> {
            JsonObject r = el.getAsJsonObject();
            if (r.get("count").getAsInt() == 0) {
                return "No players online.";
            }
            List<String> lines = new ArrayList<>();
            for (JsonElement pe : r.getAsJsonArray("players")) {
                JsonObject p = pe.getAsJsonObject();
                JsonArray pos = p.getAsJsonArray("pos");
                JsonArray front = p.getAsJsonArray("inFront");
                lines.add(p.get("name").getAsString() + "  " + p.get("world").getAsString() + "  pos x=" + pos.get(0).getAsInt()
                        + " y=" + pos.get(1).getAsInt() + " z=" + pos.get(2).getAsInt() + "  facing " + p.get("facing").getAsString()
                        + " (block in front: " + front.get(0).getAsInt() + "," + front.get(1).getAsInt() + "," + front.get(2).getAsInt() + ")  "
                        + p.get("gameMode").getAsString().toLowerCase(Locale.ROOT)
                        + lookingAtSuffix(p));
            }
            return String.join("\n", lines);
        }));
    }

    private static String lookingAtSuffix(JsonObject p) {
        JsonElement la = p.get("lookingAt");
        if (la == null || la.isJsonNull()) {
            return "";
        }
        JsonObject o = la.getAsJsonObject();
        JsonArray pos = o.getAsJsonArray("pos");
        return "  looking at " + o.get("block").getAsString() + " at " + pos.get(0).getAsInt() + "," + pos.get(1).getAsInt()
                + "," + pos.get(2).getAsInt() + " (" + o.get("face").getAsString() + " face)";
    }
}
