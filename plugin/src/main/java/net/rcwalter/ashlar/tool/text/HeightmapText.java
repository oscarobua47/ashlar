// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java port of {@code mcp-server/src/render/heightmap-view.ts}: formatting shared by
 * mc_survey's default image path and mc_render's {@code view:"heightmap"}, both of which get back a
 * one-line summary (area, min/max/median height, dominant surface materials, largest flat zone) and
 * a hypsometric-band legend from the plugin's {@code render} RPC. No Bukkit dependency.
 */
public final class HeightmapText {

    private HeightmapText() {
    }

    public record FlatZone(int x1, int z1, int x2, int z2, int y, int width, int depth) {
    }

    public record LegendBand(String color, String label) {
    }

    public record Heights(int min, int max, int median) {
    }

    /** Mirrors heightmap-view.ts's {@code HeightmapRenderFields}. */
    public record HeightmapRenderFields(
            int[] boundsFrom, // [x1, z1]
            int[] boundsTo, // [x2, z2]
            Heights heights,
            Map<String, Long> surface,
            FlatZone flatZone, // nullable
            List<LegendBand> legend,
            int liquidCells,
            int treeCells,
            int contour
    ) {
    }

    /** The one-line summary: same shape as {@code renderRelief}'s summary line, built from server-aggregated numbers. */
    public static String summaryLine(HeightmapRenderFields result) {
        int x1 = result.boundsFrom()[0], z1 = result.boundsFrom()[1];
        int x2 = result.boundsTo()[0], z2 = result.boundsTo()[1];
        int width = x2 - x1 + 1;
        int depth = z2 - z1 + 1;

        List<Map.Entry<String, Long>> surfaceEntries = new ArrayList<>(result.surface().entrySet());
        surfaceEntries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        long totalSurface = 0;
        for (Map.Entry<String, Long> e : surfaceEntries) totalSurface += e.getValue();
        if (totalSurface == 0) totalSurface = 1;
        String surfaceText;
        if (!surfaceEntries.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(4, surfaceEntries.size());
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(", ");
                Map.Entry<String, Long> e = surfaceEntries.get(i);
                long pct = Math.round((e.getValue() / (double) totalSurface) * 100);
                sb.append(ReliefText.shortBlockName(e.getKey())).append(' ').append(pct).append('%');
            }
            surfaceText = sb.toString();
        } else {
            surfaceText = "unknown";
        }

        FlatZone zone = result.flatZone();
        String zoneLine = zone != null
                ? "Largest flat zone (+/-1 block): " + zone.width() + "x" + zone.depth() + " at x=" + zone.x1() + ".." + zone.x2()
                    + " z=" + zone.z1() + ".." + zone.z2() + ", y=" + zone.y() + "."
                : "Largest flat zone: none found.";

        String liquidText = result.liquidCells() > 0 ? " Liquid cells: " + result.liquidCells() + "." : "";
        String treeText = result.treeCells() > 0 ? " Trees: " + result.treeCells() + " cells." : "";

        return "Area x=[" + x1 + ".." + x2 + "] z=[" + z1 + ".." + z2 + "] (" + width + "x" + depth + "). Surface y: min "
                + result.heights().min() + ", max " + result.heights().max() + ", median " + result.heights().median()
                + ". Surface: " + surfaceText + ". " + zoneLine + liquidText + treeText;
    }

    /** The hypsometric-band legend line, e.g. "Legend: #3f76e4 water   #2d6a1f y 60-61   ...". */
    public static String legendLine(List<LegendBand> legend) {
        StringBuilder sb = new StringBuilder("Legend: ");
        for (int i = 0; i < legend.size(); i++) {
            if (i > 0) sb.append("   ");
            sb.append(legend.get(i).color()).append(' ').append(legend.get(i).label());
        }
        return sb.toString();
    }

    public static String contourLine(int contour) {
        return contour > 0
                ? "Contour lines: every " + contour + " blocks of height (a darker line where the height crosses a multiple of " + contour + ")."
                : "Contour lines: disabled.";
    }
}
