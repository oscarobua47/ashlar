// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java port of {@code mcp-server/src/render/slice.ts}: renders a decoded {@code read_region}
 * result ({@link BlockGrid}) into either a block statistics table or an ASCII cross-section grid
 * with a legend. No Bukkit dependency.
 */
public final class SliceText {

    private SliceText() {
    }

    public static final String AIR_ID = "minecraft:air";

    public record SliceSpec(String axis, int at) {
    }

    /** Special characters tried first, most-frequent block first (air is always '.', never in this list). */
    private static final String[] SPECIAL_CHARS = {"#", "=", "+", "*", "%", "@", "&", "o", "x", "~", "^", "-", ":"};
    /** Lowercase letters not already used above, tried next. */
    private static final String FALLBACK_LETTERS = "abcdefghijklmnpqrstuvwyz";
    private static final String FALLBACK_DIGITS = "0123456789";

    /** The character assigned to the block at this rank (0 = most frequent). Never returns '.' (reserved for air). */
    public static String sliceChar(int rank) {
        if (rank < SPECIAL_CHARS.length) return SPECIAL_CHARS[rank];
        int afterSpecial = rank - SPECIAL_CHARS.length;
        if (afterSpecial < FALLBACK_LETTERS.length()) return String.valueOf(FALLBACK_LETTERS.charAt(afterSpecial));
        int afterLetters = afterSpecial - FALLBACK_LETTERS.length();
        if (afterLetters < FALLBACK_DIGITS.length()) return String.valueOf(FALLBACK_DIGITS.charAt(afterLetters));
        return "?";
    }

    /** Assigns one character to each block id, in the given (most-frequent-first) order. {@code blockId} list must exclude air. */
    public static Map<String, String> assignSliceChars(List<String> blocksMostFrequentFirst) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < blocksMostFrequentFirst.size(); i++) {
            map.put(blocksMostFrequentFirst.get(i), sliceChar(i));
        }
        return map;
    }

    private static int[] range(int lo, int hi) {
        int[] out = new int[Math.max(0, hi - lo + 1)];
        for (int v = lo, i = 0; v <= hi; v++, i++) out[i] = v;
        return out;
    }

    private static int[] rangeDesc(int lo, int hi) {
        int[] out = new int[Math.max(0, hi - lo + 1)];
        for (int v = hi, i = 0; v >= lo; v--, i++) out[i] = v;
        return out;
    }

    public record DownsampleSliceResult(String[][] grid, int[] rowsCoords, int[] colsCoords) {
    }

    /**
     * Downsamples a categorical grid by {@code step}, taking the most frequent block (mode) of each
     * step*step block as the representative cell.
     */
    public static DownsampleSliceResult downsampleSlice(String[][] grid, int[] rowsCoords, int[] colsCoords, int step) {
        if (step <= 1) {
            String[][] g = new String[grid.length][];
            for (int i = 0; i < grid.length; i++) g[i] = grid[i].clone();
            return new DownsampleSliceResult(g, rowsCoords.clone(), colsCoords.clone());
        }
        int outRows = (int) Math.ceil((double) rowsCoords.length / step);
        int outCols = (int) Math.ceil((double) colsCoords.length / step);
        String[][] outGrid = new String[outRows][outCols];
        int[] outRowsCoords = new int[outRows];
        int[] outColsCoords = new int[outCols];
        for (int c = 0; c < outCols; c++) outColsCoords[c] = colsCoords[c * step];
        for (int r = 0; r < outRows; r++) {
            outRowsCoords[r] = rowsCoords[r * step];
            int rEnd = Math.min(rowsCoords.length, (r + 1) * step);
            for (int c = 0; c < outCols; c++) {
                int cEnd = Math.min(colsCoords.length, (c + 1) * step);
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (int rr = r * step; rr < rEnd; rr++) {
                    for (int cc = c * step; cc < cEnd; cc++) {
                        String b = grid[rr][cc];
                        counts.merge(b, 1, Integer::sum);
                    }
                }
                String bestBlock = "";
                int bestCount = -1;
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    if (e.getValue() > bestCount) {
                        bestCount = e.getValue();
                        bestBlock = e.getKey();
                    }
                }
                outGrid[r][c] = bestBlock;
            }
        }
        return new DownsampleSliceResult(outGrid, outRowsCoords, outColsCoords);
    }

    private static List<String> renderGridLines(String[][] charGrid, int[] rowsCoords, int[] colsCoords) {
        int gutter = 7; // 6-wide row label + 1 space
        int tickEvery = 5;
        char[] ruler = new char[gutter + colsCoords.length];
        java.util.Arrays.fill(ruler, ' ');
        for (int c = 0; c < colsCoords.length; c += tickEvery) {
            String s = String.valueOf(colsCoords[c]);
            for (int i = 0; i < s.length() && gutter + c + i < ruler.length; i++) {
                ruler[gutter + c + i] = s.charAt(i);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(new String(ruler));
        for (int r = 0; r < charGrid.length; r++) {
            String label = padStart(String.valueOf(rowsCoords[r]), 6);
            StringBuilder row = new StringBuilder();
            for (String s : charGrid[r]) row.append(s);
            lines.add(label + " " + row);
        }
        return lines;
    }

    private static String padStart(String s, int width) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < width; i++) sb.append(' ');
        sb.append(s);
        return sb.toString();
    }

    /** Callback that reads a decoded block state given two coordinates in the slice's row/column axes. */
    private interface Getter {
        String get(int row, int col);
    }

    /**
     * Renders a single-layer cross-section as an ASCII grid with a legend. {@code axis:"y"} gives a
     * top-down slice (rows = z, cols = x); {@code axis:"x"}/{@code "z"} give a vertical elevation
     * slice (rows = y, high-to-low, cols = the other horizontal axis). Downsamples with the same
     * step rule as relief.ts when wider than 80 columns or taller than 60 rows.
     */
    public static String renderSlice(BlockGrid decoded, SliceSpec spec) {
        int minX = decoded.bounds().from()[0], minY = decoded.bounds().from()[1], minZ = decoded.bounds().from()[2];
        int maxX = decoded.bounds().to()[0], maxY = decoded.bounds().to()[1], maxZ = decoded.bounds().to()[2];

        int[] rowsCoords;
        int[] colsCoords;
        Getter get;
        String rowAxisLabel;
        String colAxisLabel;

        if (spec.axis().equals("y")) {
            if (spec.at() < minY || spec.at() > maxY) {
                throw new IllegalArgumentException("slice: y=" + spec.at() + " is outside the read region's y range [" + minY + "," + maxY + "]");
            }
            rowsCoords = range(minZ, maxZ);
            colsCoords = range(minX, maxX);
            get = (z, x) -> decoded.at(x, spec.at(), z);
            rowAxisLabel = "z";
            colAxisLabel = "x";
        } else if (spec.axis().equals("x")) {
            if (spec.at() < minX || spec.at() > maxX) {
                throw new IllegalArgumentException("slice: x=" + spec.at() + " is outside the read region's x range [" + minX + "," + maxX + "]");
            }
            rowsCoords = rangeDesc(minY, maxY);
            colsCoords = range(minZ, maxZ);
            get = (y, z) -> decoded.at(spec.at(), y, z);
            rowAxisLabel = "y";
            colAxisLabel = "z";
        } else {
            if (spec.at() < minZ || spec.at() > maxZ) {
                throw new IllegalArgumentException("slice: z=" + spec.at() + " is outside the read region's z range [" + minZ + "," + maxZ + "]");
            }
            rowsCoords = rangeDesc(minY, maxY);
            colsCoords = range(minX, maxX);
            get = (y, x) -> decoded.at(x, y, spec.at());
            rowAxisLabel = "y";
            colAxisLabel = "x";
        }

        String[][] grid = new String[rowsCoords.length][colsCoords.length];
        for (int r = 0; r < rowsCoords.length; r++) {
            for (int c = 0; c < colsCoords.length; c++) {
                grid[r][c] = get.get(rowsCoords[r], colsCoords[c]);
            }
        }

        Map<String, Integer> freq = new LinkedHashMap<>();
        boolean hasAir = false;
        for (String[] row : grid) {
            for (String b : row) {
                if (b.equals(AIR_ID)) {
                    hasAir = true;
                    continue;
                }
                freq.merge(b, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> freqEntries = new ArrayList<>(freq.entrySet());
        freqEntries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> distinctSorted = new ArrayList<>();
        for (Map.Entry<String, Integer> e : freqEntries) distinctSorted.add(e.getKey());
        Map<String, String> charMap = assignSliceChars(distinctSorted);

        int step = ReliefText.computeStep(colsCoords.length, rowsCoords.length);
        String[][] outGrid;
        int[] outRowsCoords;
        int[] outColsCoords;
        if (step > 1) {
            DownsampleSliceResult ds = downsampleSlice(grid, rowsCoords, colsCoords, step);
            outGrid = ds.grid();
            outRowsCoords = ds.rowsCoords();
            outColsCoords = ds.colsCoords();
        } else {
            outGrid = grid;
            outRowsCoords = rowsCoords;
            outColsCoords = colsCoords;
        }

        String[][] charGrid = new String[outGrid.length][];
        for (int r = 0; r < outGrid.length; r++) {
            charGrid[r] = new String[outGrid[r].length];
            for (int c = 0; c < outGrid[r].length; c++) {
                String b = outGrid[r][c];
                charGrid[r][c] = b.equals(AIR_ID) ? "." : charMap.getOrDefault(b, "?");
            }
        }
        List<String> gridLines = renderGridLines(charGrid, outRowsCoords, outColsCoords);

        String header = "Slice axis=" + spec.axis() + " at=" + spec.at() + " (rows=" + rowAxisLabel + " " + rowsCoords.length
                + ", cols=" + colAxisLabel + " " + colsCoords.length + ")"
                + (step > 1 ? ", downsampled 1 char = " + step + "x" + step + " cells (most frequent block)" : "")
                + ".";

        List<String> legendLines = new ArrayList<>();
        legendLines.add("Legend:");
        if (hasAir) legendLines.add("  . " + AIR_ID);
        for (String block : distinctSorted) {
            legendLines.add("  " + charMap.get(block) + " " + block);
        }

        List<String> all = new ArrayList<>();
        all.add(header);
        all.add("");
        all.addAll(gridLines);
        all.add("");
        all.addAll(legendLines);
        return String.join("\n", all);
    }

    /** Block-frequency table + bounding box, for mc_inspect with no {@code slice}. */
    public static String renderStats(BlockGrid decoded, String world) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        java.util.Iterator<String> it = decoded.iterator();
        while (it.hasNext()) {
            counts.merge(it.next(), 1, Integer::sum);
        }
        long total = decoded.volume() != 0 ? decoded.volume() : 1;
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        int fx = decoded.bounds().from()[0], fy = decoded.bounds().from()[1], fz = decoded.bounds().from()[2];
        int tx = decoded.bounds().to()[0], ty = decoded.bounds().to()[1], tz = decoded.bounds().to()[2];
        List<String> lines = new ArrayList<>();
        lines.add("Region " + world + " x=[" + fx + ".." + tx + "] y=[" + fy + ".." + ty + "] z=[" + fz + ".." + tz + "] ("
                + decoded.size().dx() + "x" + decoded.size().dy() + "x" + decoded.size().dz() + " = " + decoded.volume() + " blocks).");
        lines.add("");
        lines.add(sorted.size() + " distinct block state(s):");
        for (Map.Entry<String, Integer> e : sorted) {
            String pct = toFixed1((e.getValue() / (double) total) * 100);
            lines.add("  " + padStart(String.valueOf(e.getValue()), 8) + "  " + padStart(pct, 5) + "%  " + e.getKey());
        }
        return String.join("\n", lines);
    }

    /** Mirrors JS's {@code Number.prototype.toFixed(1)}: round-half-away-from-zero to one decimal place. */
    private static String toFixed1(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
