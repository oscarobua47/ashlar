// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.engine.RegionData;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.RpcHandler;
import net.rcwalter.ashlar.tool.ArgParse;
import net.rcwalter.ashlar.tool.Tool;
import net.rcwalter.ashlar.tool.ToolArgError;
import net.rcwalter.ashlar.tool.ToolResult;
import net.rcwalter.ashlar.tool.ToolRunner;
import net.rcwalter.ashlar.tool.ToolSpec;
import net.rcwalter.ashlar.tool.text.BlockGrid;
import net.rcwalter.ashlar.tool.text.SliceText;
import net.rcwalter.ashlar.tool.text.ToolText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.rcwalter.ashlar.tool.mc.JsonUtil.intArray;

/** Pure-Java port of {@code mcp-server/src/tools/mc-inspect.ts}. No text differences from the TS tool. */
public final class McInspect implements Tool {

    private static final long MAX_VOLUME = 200_000;

    private final ToolSpec spec = ToolSpec.load("mc_inspect");
    private final RpcHandler readRegionHandler;

    public McInspect(RpcHandler readRegionHandler) {
        this.readRegionHandler = readRegionHandler;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    /** Client-side pre-check mirroring mc-inspect.ts's {@code MAX_VOLUME} guard, exposed for unit testing. */
    static void checkVolume(long volume) {
        if (volume > MAX_VOLUME) {
            throw new ToolArgError("mc_inspect region volume " + volume
                    + " exceeds the 200,000-block limit. Reduce the from/to range or split it into several calls.");
        }
    }

    record SliceArg(String axis, int at) {
    }

    record Args(String world, int[] from, int[] to, SliceArg slice) {
        static Args parse(JsonObject o) {
            String world = ArgParse.optString(o, "world");
            int[] from = ArgParse.requireCoords3(o, "from");
            int[] to = ArgParse.requireCoords3(o, "to");
            SliceArg slice = null;
            if (ArgParse.has(o, "slice")) {
                JsonObject s = ArgParse.requireObject(o.get("slice"), "slice");
                String axis = ArgParse.requireEnum(s, "axis", List.of("x", "y", "z"));
                int at = ArgParse.requireInt(s, "at");
                slice = new SliceArg(axis, at);
            }
            return new Args(world, from, to, slice);
        }
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_inspect", () -> {
            Args a = Args.parse(args);
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

            return readRegionHandler.handle(ctx, params).thenApply(el -> {
                JsonObject r = el.getAsJsonObject();
                RegionData regionData = RegionData.fromJson(r);
                BlockGrid decoded = BlockGrid.fromRegionData(regionData);

                List<ToolText.SignEntry> signs = new ArrayList<>();
                for (JsonElement se : r.getAsJsonArray("signs")) {
                    JsonObject s = se.getAsJsonObject();
                    JsonArray pos = s.getAsJsonArray("pos");
                    signs.add(new ToolText.SignEntry(
                            new int[]{pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt()},
                            s.get("block").getAsString(),
                            JsonUtil.toStringList(s.getAsJsonArray("front")),
                            JsonUtil.toStringList(s.getAsJsonArray("back")),
                            s.get("waxed").getAsBoolean()));
                }
                boolean signsTruncated = r.has("signsTruncated") && r.get("signsTruncated").getAsBoolean();

                String worldFromResult = (r.has("world") && r.get("world").isJsonPrimitive() && r.get("world").getAsJsonPrimitive().isString())
                        ? r.get("world").getAsString() : null;
                String worldName = worldFromResult != null ? worldFromResult : (a.world() != null ? a.world() : "(default)");

                return a.slice() != null
                        ? ToolText.inspectSliceText(decoded, new SliceText.SliceSpec(a.slice().axis(), a.slice().at()), signs, signsTruncated)
                        : ToolText.inspectStatsText(decoded, worldName, signs, signsTruncated);
            });
        });
    }
}
