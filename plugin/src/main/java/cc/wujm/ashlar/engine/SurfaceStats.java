// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared surface-material-count -> JSON conversion, used by both
 * {@link HeightmapTask} and {@link HeightmapImageTask}: sorted descending,
 * capped at {@link #MAX_SURFACE_ENTRIES}, the rest merged into {@code "other"}.
 */
final class SurfaceStats {

    private static final int MAX_SURFACE_ENTRIES = 16;

    private SurfaceStats() {
    }

    static JsonObject buildSurfaceJson(Map<String, Long> counts) {
        JsonObject surface = new JsonObject();
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        long other = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < MAX_SURFACE_ENTRIES) {
                surface.addProperty(sorted.get(i).getKey(), sorted.get(i).getValue());
            } else {
                other += sorted.get(i).getValue();
            }
        }
        if (other > 0) {
            surface.addProperty("other", other);
        }
        return surface;
    }
}
