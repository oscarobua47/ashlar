// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.ContentBlock;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import cc.wujm.ashlar.tool.text.HeightmapText;
import cc.wujm.ashlar.tool.text.ToolText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static cc.wujm.ashlar.tool.mc.JsonUtil.intArray;
import static cc.wujm.ashlar.tool.mc.JsonUtil.toIntArrayAny;

/** Pure-Java port of {@code mcp-server/src/tools/mc-render.ts}. No text differences from the TS tool. */
public final class McRender implements Tool {

    private static final long MAX_VOLUME = 200_000;
    private static final long MAX_HEIGHTMAP_AREA = 200_000;
    private static final List<String> VIEWS = List.of("top", "north", "south", "east", "west", "slice", "heightmap");
    private static final List<String> HEIGHTMAP_TYPES = List.of("SOLID", "SOLID_OR_LIQUID", "SOLID_OR_LIQUID_NO_LEAVES", "ANY");
    private static final List<String> SLICE_AXES = List.of("x", "y", "z");

    private final ToolSpec spec = ToolSpec.load("mc_render");
    private final RpcHandler renderHandler;

    public McRender(RpcHandler renderHandler) {
        this.renderHandler = renderHandler;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record SliceArg(String axis, int at) {
    }

    record Args(String world, int[] from, int[] to, String view, SliceArg slice, Integer scale, Integer grid,
                String heightmapType, Integer contour) {
        static Args parse(JsonObject o) {
            String world = ArgParse.optString(o, "world");
            int[] from = ArgParse.requireCoords2Or3(o, "from");
            int[] to = ArgParse.requireCoords2Or3(o, "to");
            String view = ArgParse.optEnum(o, "view", VIEWS, null);
            SliceArg slice = null;
            if (ArgParse.has(o, "slice")) {
                JsonObject s = ArgParse.requireObject(o.get("slice"), "slice");
                String axis = ArgParse.requireEnum(s, "axis", SLICE_AXES);
                int at = ArgParse.requireInt(s, "at");
                slice = new SliceArg(axis, at);
            }
            Integer scale = ArgParse.optInt(o, "scale");
            if (scale != null && (scale < 0 || scale > 16)) {
                throw new ToolArgError("scale: must be between 0 and 16, got " + scale);
            }
            Integer grid = ArgParse.optInt(o, "grid");
            if (grid != null && (grid < 0 || grid > 64)) {
                throw new ToolArgError("grid: must be between 0 and 64, got " + grid);
            }
            String heightmapType = ArgParse.optEnum(o, "heightmapType", HEIGHTMAP_TYPES, null);
            Integer contour = ArgParse.optInt(o, "contour");
            if (contour != null && (contour < 0 || contour > 4096)) {
                throw new ToolArgError("contour: must be between 0 and 4096, got " + contour);
            }
            return new Args(world, from, to, view, slice, scale, grid, heightmapType, contour);
        }
    }

    /** These four checks mirror mc-render.ts's plain body-level throws; extracted for unit testing. */
    static void checkShapeCompat(boolean footprintOnly, String resolvedView) {
        if (footprintOnly && !resolvedView.equals("top") && !resolvedView.equals("heightmap")) {
            throw new ToolArgError("mc_render view \"" + resolvedView + "\" needs [x, y, z] corners; only \"top\" and \"heightmap\" accept [x, z].");
        }
    }

    static void checkFromToLengthsMatch(boolean footprintOnly, int fromLen, int toLen) {
        if (footprintOnly && fromLen != toLen) {
            throw new ToolArgError("mc_render: from and to must both be [x, z] or both be [x, y, z].");
        }
    }

    static void checkArea(String resolvedView, long area) {
        if (area > MAX_HEIGHTMAP_AREA) {
            throw new ToolArgError("mc_render " + resolvedView + " area " + area
                    + " exceeds the 200,000-cell limit. Reduce the from/to range or split it into several calls.");
        }
    }

    static void checkVolume(long volume) {
        if (volume > MAX_VOLUME) {
            throw new ToolArgError("mc_render region volume " + volume
                    + " exceeds the 200,000-block limit. Reduce the from/to range or split it into several calls.");
        }
    }

    static void checkSliceRequired(String resolvedView, SliceArg slice) {
        if (resolvedView.equals("slice") && slice == null) {
            throw new ToolArgError("mc_render view \"slice\" requires the \"slice\" parameter: {\"axis\": \"x\"|\"y\"|\"z\", \"at\": <coordinate>}.");
        }
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runContent("mc_render", () -> {
            Args a = Args.parse(args);
            String resolvedView = a.view() != null ? a.view() : "top";
            boolean footprintOnly = a.from().length == 2 || a.to().length == 2;
            checkShapeCompat(footprintOnly, resolvedView);
            checkFromToLengthsMatch(footprintOnly, a.from().length, a.to().length);
            int fx = a.from()[0], fz = a.from().length == 2 ? a.from()[1] : a.from()[2];
            int tx = a.to()[0], tz = a.to().length == 2 ? a.to()[1] : a.to()[2];
            int fy = a.from().length == 3 ? a.from()[1] : 0, ty = a.to().length == 3 ? a.to()[1] : 0;
            int x1 = Math.min(fx, tx), y1 = Math.min(fy, ty), z1 = Math.min(fz, tz);
            int x2 = Math.max(fx, tx), y2 = Math.max(fy, ty), z2 = Math.max(fz, tz);

            if (resolvedView.equals("heightmap") || resolvedView.equals("top")) {
                checkArea(resolvedView, (long) (x2 - x1 + 1) * (z2 - z1 + 1));
            } else {
                checkVolume((long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1));
            }
            checkSliceRequired(resolvedView, a.slice());

            JsonObject params = new JsonObject();
            if (a.world() != null) {
                params.addProperty("world", a.world());
            }
            boolean useFootprint = resolvedView.equals("heightmap") || footprintOnly;
            params.add("from", useFootprint ? intArray(x1, z1) : intArray(x1, y1, z1));
            params.add("to", useFootprint ? intArray(x2, z2) : intArray(x2, y2, z2));
            if (a.view() != null) {
                params.addProperty("view", a.view());
            }
            if (a.slice() != null) {
                JsonObject sliceJson = new JsonObject();
                sliceJson.addProperty("axis", a.slice().axis());
                sliceJson.addProperty("at", a.slice().at());
                params.add("slice", sliceJson);
            }
            if (a.scale() != null) {
                params.addProperty("scale", a.scale());
            }
            if (a.grid() != null) {
                params.addProperty("grid", a.grid());
            }
            if (resolvedView.equals("heightmap")) {
                if (a.heightmapType() != null) {
                    params.addProperty("type", a.heightmapType());
                }
                if (a.contour() != null) {
                    params.addProperty("contour", a.contour());
                }
            }

            return renderHandler.handle(ctx, params).thenApply(el -> {
                JsonObject r = el.getAsJsonObject();
                JsonObject bounds = r.getAsJsonObject("bounds");
                int[] boundsFrom = toIntArrayAny(bounds.getAsJsonArray("from"));
                int[] boundsTo = toIntArrayAny(bounds.getAsJsonArray("to"));
                JsonObject axes = r.getAsJsonObject("axes");
                JsonArray topLeftArr = r.getAsJsonArray("topLeft");
                int width = r.get("width").getAsInt();
                int height = r.get("height").getAsInt();
                int scaleOut = r.get("scale").getAsInt();
                int grid = r.get("grid").getAsInt();
                String view = r.get("view").getAsString();

                String text;
                if (view.equals("heightmap")) {
                    HeightmapText.HeightmapRenderFields fields = HeightmapJson.renderFields(r);
                    text = ToolText.renderText(view, boundsFrom, boundsTo, width, height, scaleOut,
                            axes.get("right").getAsString(), axes.get("down").getAsString(),
                            topLeftArr.get(0).getAsInt(), topLeftArr.get(1).getAsInt(), grid, fields, null);
                } else {
                    List<ToolText.ColorLegendEntry> legend = new ArrayList<>();
                    for (JsonElement le : r.getAsJsonArray("legend")) {
                        JsonObject l = le.getAsJsonObject();
                        legend.add(new ToolText.ColorLegendEntry(l.get("color").getAsString(), l.get("block").getAsString(), l.get("pixels").getAsInt()));
                    }
                    text = ToolText.renderText(view, boundsFrom, boundsTo, width, height, scaleOut,
                            axes.get("right").getAsString(), axes.get("down").getAsString(),
                            topLeftArr.get(0).getAsInt(), topLeftArr.get(1).getAsInt(), grid, null, legend);
                }
                return List.of(ContentBlock.image(r.get("png").getAsString(), "image/png"), ContentBlock.text(text));
            });
        });
    }
}
