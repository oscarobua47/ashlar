// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.tool.text.HeightmapText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared parsing of the {@code heightmap}/{@code render(view:"heightmap")} RPC result shapes into
 * {@link HeightmapText} inputs, used by both {@code McSurvey} and {@code McRender}.
 */
final class HeightmapJson {

    private HeightmapJson() {
    }

    static Map<String, Long> surfaceMap(JsonObject surface) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : surface.entrySet()) {
            map.put(e.getKey(), e.getValue().getAsLong());
        }
        return map;
    }

    static int[][] toIntGrid(JsonArray rows) {
        int[][] out = new int[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            JsonArray row = rows.get(i).getAsJsonArray();
            int[] r = new int[row.size()];
            for (int j = 0; j < row.size(); j++) {
                r[j] = row.get(j).getAsInt();
            }
            out[i] = r;
        }
        return out;
    }

    static List<HeightmapText.LegendBand> legendBands(JsonArray legend) {
        List<HeightmapText.LegendBand> out = new ArrayList<>();
        for (JsonElement el : legend) {
            JsonObject b = el.getAsJsonObject();
            out.add(new HeightmapText.LegendBand(b.get("color").getAsString(), b.get("label").getAsString()));
        }
        return out;
    }

    static HeightmapText.FlatZone flatZone(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        JsonObject z = el.getAsJsonObject();
        return new HeightmapText.FlatZone(z.get("x1").getAsInt(), z.get("z1").getAsInt(), z.get("x2").getAsInt(),
                z.get("z2").getAsInt(), z.get("y").getAsInt(), z.get("width").getAsInt(), z.get("depth").getAsInt());
    }

    /** Parses a {@code render(view:"heightmap")} result into {@link HeightmapText.HeightmapRenderFields}. */
    static HeightmapText.HeightmapRenderFields renderFields(JsonObject result) {
        JsonObject bounds = result.getAsJsonObject("bounds");
        int[] from = JsonUtil.toIntArrayAny(bounds.getAsJsonArray("from"));
        int[] to = JsonUtil.toIntArrayAny(bounds.getAsJsonArray("to"));
        JsonObject heights = result.getAsJsonObject("heights");
        HeightmapText.Heights h = new HeightmapText.Heights(
                heights.get("min").getAsInt(), heights.get("max").getAsInt(), heights.get("median").getAsInt());
        return new HeightmapText.HeightmapRenderFields(
                from, to, h,
                surfaceMap(result.getAsJsonObject("surface")),
                flatZone(result.get("flatZone")),
                legendBands(result.getAsJsonArray("legend")),
                result.get("liquidCells").getAsInt(),
                result.has("treeCells") ? result.get("treeCells").getAsInt() : 0,
                result.get("contour").getAsInt());
    }
}
