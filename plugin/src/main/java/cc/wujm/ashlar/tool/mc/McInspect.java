// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.engine.RegionData;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import cc.wujm.ashlar.tool.text.BlockGrid;
import cc.wujm.ashlar.tool.text.SliceText;
import cc.wujm.ashlar.tool.text.ToolText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static cc.wujm.ashlar.tool.mc.JsonUtil.intArray;

/** Pure-Java port of {@code mcp-server/src/tools/mc-inspect.ts}. No text differences from the TS tool. */
public final class McInspect implements Tool {

    private static final long MAX_VOLUME = 200_000;
    private static final long MAX_COLUMNS = 1024;
    private static final List<String> FORMATS = List.of("stats", "columns");

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

    /** Client-side pre-check for {@code format:"columns"}'s 1024-column cap, exposed for unit testing. */
    static void checkColumns(long columns) {
        if (columns > MAX_COLUMNS) {
            throw new ToolArgError("mc_inspect format: \"columns\" region has " + columns
                    + " columns, exceeding the 1024-column limit. Reduce the x/z range.");
        }
    }

    record SliceArg(String axis, int at) {
    }

    record Args(String world, int[] from, int[] to, SliceArg slice, String format) {
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
            String format = ArgParse.optEnum(o, "format", FORMATS, "stats");
            if (format.equals("columns") && slice != null) {
                throw new ToolArgError("`slice` and `format: \"columns\"` are exclusive");
            }
            return new Args(world, from, to, slice, format);
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
            if (a.format().equals("columns")) {
                long columns = (long) (x2 - x1 + 1) * (z2 - z1 + 1);
                checkColumns(columns);
            }

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

                if (a.slice() != null) {
                    return ToolText.inspectSliceText(decoded, new SliceText.SliceSpec(a.slice().axis(), a.slice().at()), signs, signsTruncated);
                }
                if (a.format().equals("columns")) {
                    return ToolText.inspectColumnsText(decoded, worldName, signs, signsTruncated);
                }
                return ToolText.inspectStatsText(decoded, worldName, signs, signsTruncated);
            });
        });
    }
}
