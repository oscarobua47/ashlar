// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a decoded {@link BlockGrid} region as {@code mc_inspect}'s {@code format:"columns"}: for
 * every column in the region, an exact bottom-to-top run-length list of block states, one line per
 * column, z outer / x inner (row-major, like reading a map). Complements {@link SliceText}'s stats
 * table (block-frequency counts) and ASCII slice (one layer at a time): columns gives an exact read
 * of everything from bottom to top in a single call, which is the shape needed to repair a hole (a
 * crater, a cave, a gap in a wall) without probing layer by layer. No Bukkit dependency.
 */
public final class ColumnsText {

    private ColumnsText() {
    }

    private static String stripMinecraftPrefix(String block) {
        return block.startsWith("minecraft:") ? block.substring("minecraft:".length()) : block;
    }

    private static String formatRun(int yStart, int yEnd, String block) {
        String id = stripMinecraftPrefix(block);
        return yStart == yEnd ? (yStart + " " + id) : (yStart + "-" + yEnd + " " + id);
    }

    /** One column's runs, bottom (low y) to top (high y): {@code "y1-y2 block | y3-y4 block | ..."}. */
    static String renderColumnRuns(BlockGrid decoded, int x, int z, int y1, int y2) {
        List<String> runs = new ArrayList<>();
        int runStart = y1;
        String runBlock = decoded.at(x, y1, z);
        for (int y = y1 + 1; y <= y2; y++) {
            String block = decoded.at(x, y, z);
            if (!block.equals(runBlock)) {
                runs.add(formatRun(runStart, y - 1, runBlock));
                runStart = y;
                runBlock = block;
            }
        }
        runs.add(formatRun(runStart, y2, runBlock));
        return String.join(" | ", runs);
    }

    /** Full per-column table (header + one line per column), for {@code mc_inspect} with {@code format:"columns"}. */
    public static String renderColumns(BlockGrid decoded, String world) {
        int fx = decoded.bounds().from()[0], fy = decoded.bounds().from()[1], fz = decoded.bounds().from()[2];
        int tx = decoded.bounds().to()[0], ty = decoded.bounds().to()[1], tz = decoded.bounds().to()[2];
        long columns = (long) decoded.size().dx() * decoded.size().dz();

        List<String> lines = new ArrayList<>();
        lines.add("Region " + world + " x=[" + fx + ".." + tx + "] y=[" + fy + ".." + ty + "] z=[" + fz + ".." + tz + "] ("
                + decoded.size().dx() + "x" + decoded.size().dy() + "x" + decoded.size().dz() + " = " + decoded.volume() + " blocks), "
                + columns + " columns, runs bottom to top (ids without the minecraft: prefix):");
        for (int z = fz; z <= tz; z++) {
            for (int x = fx; x <= tx; x++) {
                lines.add(x + "," + z + ": " + renderColumnRuns(decoded, x, z, fy, ty));
            }
        }
        return String.join("\n", lines);
    }
}
