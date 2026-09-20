// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java port of {@code mcp-server/src/render/relief.ts}: renders a
 * {@code heightmap} result into an ASCII relief map plus a numeric matrix, a
 * legend, and a one-line summary ("largest flat zone" included). No Bukkit
 * dependency, so this class is unit-testable in isolation (docs/private/prompts/step7b-prompt.md).
 */
public final class ReliefText {

    private ReliefText() {
    }

    /** 8-level bucket characters, lowest to highest; the true maximum gets '@' instead of '#'. */
    private static final String[] BUCKET_CHARS = {".", ",", ":", "-", "=", "+", "*", "#"};

    /** {@code classes[zi][xi]} values, matching the plugin's {@code SurfaceClass} codes. */
    public static final int CLASS_GROUND = 0;
    public static final int CLASS_LIQUID = 1;
    public static final int CLASS_VEGETATION = 2;

    /** Areas up to this many cells get the numeric matrix by default (40x40). */
    public static final int MATRIX_AUTO_CELLS = 1600;

    /** Mirrors relief.ts's {@code ReliefInput}. {@code matrix} is {@code null} for "unset" (TS {@code undefined}). */
    public record ReliefInput(
            int[] from, // [x1, z1]
            int[] to, // [x2, z2]
            int[][] heights, // heights[zi][xi]
            int[][] classes, // classes[zi][xi]
            Map<String, Long> surface,
            Boolean matrix
    ) {
    }

    public record LegendEntry(String symbol, String label) {
    }

    public record FlatZone(int row1, int row2, int col1, int col2, int rows, int cols) {
    }

    public record DownsampleResult(int[][] heights, int[][] classes) {
    }

    /** step = max(1, ceil(width/80), ceil(depth/60)). */
    public static int computeStep(int width, int depth) {
        return Math.max(1, Math.max(ceilDiv(width, 80), ceilDiv(depth, 60)));
    }

    private static int ceilDiv(int a, int b) {
        return (int) Math.ceil((double) a / b);
    }

    /**
     * Median-downsamples a heights/classes grid by {@code step}. Each output cell takes the median
     * height of its step*step source cells (vegetation cells excluded from that median where any
     * non-vegetation cell exists); its class is liquid if any source cell is liquid, else vegetation
     * if any source cell is vegetation, else ground. A no-op (returns new arrays with the same values)
     * when {@code step} is 1.
     */
    public static DownsampleResult downsample(int[][] heights, int[][] classes, int step) {
        int zLen = heights.length;
        int xLen = zLen > 0 ? heights[0].length : 0;
        if (step <= 1) {
            int[][] outH = new int[heights.length][];
            for (int i = 0; i < heights.length; i++) outH[i] = heights[i].clone();
            int[][] outC = new int[classes.length][];
            for (int i = 0; i < classes.length; i++) outC[i] = classes[i].clone();
            return new DownsampleResult(outH, outC);
        }
        int outRows = ceilDiv(zLen, step);
        int outCols = ceilDiv(xLen, step);
        int[][] outHeights = new int[outRows][outCols];
        int[][] outClasses = new int[outRows][outCols];
        for (int rz = 0; rz < outRows; rz++) {
            for (int rx = 0; rx < outCols; rx++) {
                List<Integer> vals = new ArrayList<>();
                List<Integer> allVals = new ArrayList<>();
                boolean anyLiquid = false;
                boolean anyVegetation = false;
                int zEnd = Math.min(zLen, (rz + 1) * step);
                int xEnd = Math.min(xLen, (rx + 1) * step);
                for (int z = rz * step; z < zEnd; z++) {
                    for (int x = rx * step; x < xEnd; x++) {
                        int h = heights[z][x];
                        int cls = classes[z][x];
                        allVals.add(h);
                        if (cls == CLASS_LIQUID) anyLiquid = true;
                        else if (cls == CLASS_VEGETATION) anyVegetation = true;
                        if (cls != CLASS_VEGETATION) vals.add(h);
                    }
                }
                List<Integer> median = vals.isEmpty() ? allVals : vals;
                median.sort(Integer::compareTo);
                outHeights[rz][rx] = median.get(median.size() / 2);
                outClasses[rz][rx] = anyLiquid ? CLASS_LIQUID : anyVegetation ? CLASS_VEGETATION : CLASS_GROUND;
            }
        }
        return new DownsampleResult(outHeights, outClasses);
    }

    /** One cell's relief character: '~' for liquid, 'T' for vegetation, '=' for flat (min==max), else an 8-level bucket, '@' at the true max. */
    public static String reliefChar(int height, int min, int max, int cellClass) {
        if (cellClass == CLASS_LIQUID) return "~";
        if (cellClass == CLASS_VEGETATION) return "T";
        if (max == min) return "=";
        if (height >= max) return "@";
        double frac = (double) (height - min) / (max - min);
        int bucket = Math.min(7, Math.max(0, (int) Math.floor(frac * 8)));
        return BUCKET_CHARS[bucket];
    }

    /** Builds the y-range legend for the 8 buckets plus '@' (max), '~' if any liquid cell exists, and 'T' if any vegetation cell exists. */
    public static List<LegendEntry> buildLegend(int min, int max, boolean hasLiquid, boolean hasVegetation) {
        List<LegendEntry> entries = new ArrayList<>();
        if (min == max) {
            entries.add(new LegendEntry("=", String.valueOf(min)));
        } else {
            int range = max - min;
            for (int b = 0; b < 8; b++) {
                int lo = min + Math.floorDiv(b * range, 8);
                int hi = min + ceilDivExact((b + 1) * range, 8) - 1;
                if (b == 7) hi = Math.max(lo, max - 1);
                if (hi < lo) hi = lo;
                String label = lo == hi ? String.valueOf(lo) : lo + "-" + hi;
                entries.add(new LegendEntry(BUCKET_CHARS[b], label));
            }
            entries.add(new LegendEntry("@", String.valueOf(max)));
        }
        if (hasLiquid) {
            entries.add(new LegendEntry("~", "liquid surface"));
        }
        if (hasVegetation) {
            entries.add(new LegendEntry("T", "trees / vegetation"));
        }
        return entries;
    }

    /** {@code Math.ceil(a/b)} for integers, matching JS's {@code Math.ceil} on an exact integer division. */
    private static int ceilDivExact(int a, int b) {
        return (int) Math.ceil((double) a / b);
    }

    /**
     * Largest axis-aligned rectangle of cells within {@code tolerance} blocks of {@code median}, via the
     * standard "largest rectangle in a binary matrix" histogram/stack algorithm, O(rows*cols). Vegetation
     * cells (per {@code classes}, may be {@code null} to consider every cell) are never flat.
     */
    public static FlatZone largestFlatZone(int[][] heights, int median, int tolerance, int[][] classes) {
        int rows = heights.length;
        if (rows == 0) return null;
        int cols = heights[0].length;
        if (cols == 0) return null;

        boolean[][] flat = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                boolean isVegetation = classes != null && classes[r][c] == CLASS_VEGETATION;
                flat[r][c] = !isVegetation && Math.abs(heights[r][c] - median) <= tolerance;
            }
        }

        int[] hist = new int[cols];
        int bestArea = -1, bestRow1 = 0, bestRow2 = 0, bestCol1 = 0, bestCol2 = 0;
        int[] stack = new int[cols + 1];

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                hist[c] = flat[r][c] ? hist[c] + 1 : 0;
            }
            int sp = 0;
            for (int c = 0; c <= cols; c++) {
                int h = c == cols ? 0 : hist[c];
                while (sp > 0 && hist[stack[sp - 1]] >= h) {
                    int top = stack[--sp];
                    int barHeight = hist[top];
                    int left = sp > 0 ? stack[sp - 1] + 1 : 0;
                    int width = c - left;
                    int area = barHeight * width;
                    if (barHeight > 0 && area > bestArea) {
                        bestArea = area;
                        bestRow1 = r - barHeight + 1;
                        bestRow2 = r;
                        bestCol1 = left;
                        bestCol2 = c - 1;
                    }
                }
                stack[sp++] = c;
            }
        }
        if (bestArea < 0) return null;
        return new FlatZone(bestRow1, bestRow2, bestCol1, bestCol2, bestRow2 - bestRow1 + 1, bestCol2 - bestCol1 + 1);
    }

    /** Overload for callers with no per-cell classes (every cell eligible). */
    public static FlatZone largestFlatZone(int[][] heights, int median, int tolerance) {
        return largestFlatZone(heights, median, tolerance, null);
    }

    private static int median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    /** Exported for reuse by mc_survey's image-format path and mc_render's {@code view:"heightmap"} text (heightmap-view.ts). */
    public static String shortBlockName(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static List<String> renderMapLines(String[][] charGrid, int x1, int z1, int step) {
        int cols = charGrid.length > 0 ? charGrid[0].length : 0;
        int gutter = 7; // 6-wide z label + 1 space
        int tickEvery = 5;
        char[] ruler = new char[gutter + cols];
        java.util.Arrays.fill(ruler, ' ');
        for (int c = 0; c < cols; c += tickEvery) {
            int worldX = x1 + c * step;
            String s = String.valueOf(worldX);
            for (int i = 0; i < s.length() && gutter + c + i < ruler.length; i++) {
                ruler[gutter + c + i] = s.charAt(i);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(new String(ruler));
        for (int r = 0; r < charGrid.length; r++) {
            int worldZ = z1 + r * step;
            String label = padStart(String.valueOf(worldZ), 6);
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

    /** Exported for reuse by mc_survey's image-format path ({@code matrix: true} appends this after the image's text block). */
    public static List<String> renderMatrix(int[][] heights, int x1, int z1, int step) {
        String header = step > 1
                ? "Height matrix (downsampled: 1 cell = " + step + "x" + step + " blocks, median height; z rows top-to-bottom, "
                    + "x columns left-to-right, top-left = x=" + x1 + " z=" + z1 + "):"
                : "Height matrix (z rows top-to-bottom, x columns left-to-right, top-left = x=" + x1 + " z=" + z1 + "):";
        List<String> lines = new ArrayList<>();
        lines.add(header);
        for (int zi = 0; zi < heights.length; zi++) {
            StringBuilder row = new StringBuilder();
            for (int i = 0; i < heights[zi].length; i++) {
                if (i > 0) row.append(' ');
                row.append(heights[zi][i]);
            }
            lines.add("z=" + (z1 + zi * step) + ": " + row);
        }
        return lines;
    }

    /** Renders the full text block: summary line, ASCII map, legend, numeric matrix. Pure ASCII. */
    public static String renderRelief(ReliefInput input) {
        int x1 = input.from()[0], z1 = input.from()[1];
        int x2 = input.to()[0], z2 = input.to()[1];
        int width = x2 - x1 + 1;
        int depth = z2 - z1 + 1;
        int step = computeStep(width, depth);

        DownsampleResult down = downsample(input.heights(), input.classes(), step);
        int[][] heights = down.heights();
        int[][] classes = down.classes();

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        List<Integer> flatVals = new ArrayList<>();
        List<Integer> allVals = new ArrayList<>();
        for (int zi = 0; zi < input.heights().length; zi++) {
            for (int xi = 0; xi < input.heights()[zi].length; xi++) {
                int h = input.heights()[zi][xi];
                allVals.add(h);
                if (input.classes()[zi][xi] != CLASS_VEGETATION) {
                    flatVals.add(h);
                    if (h < min) min = h;
                    if (h > max) max = h;
                }
            }
        }
        List<Integer> statVals = !flatVals.isEmpty() ? flatVals : allVals;
        if (flatVals.isEmpty()) {
            for (int h : allVals) {
                if (h < min) min = h;
                if (h > max) max = h;
            }
        }
        int med = median(statVals);
        boolean hasLiquid = false;
        boolean hasVegetation = false;
        for (int[] row : input.classes()) {
            for (int c : row) {
                if (c == CLASS_LIQUID) hasLiquid = true;
                if (c == CLASS_VEGETATION) hasVegetation = true;
            }
        }

        String[][] charGrid = new String[heights.length][];
        for (int zi = 0; zi < heights.length; zi++) {
            charGrid[zi] = new String[heights[zi].length];
            for (int xi = 0; xi < heights[zi].length; xi++) {
                charGrid[zi][xi] = reliefChar(heights[zi][xi], min, max, classes[zi][xi]);
            }
        }
        List<String> mapLines = renderMapLines(charGrid, x1, z1, step);

        List<LegendEntry> legend = buildLegend(min, max, hasLiquid, hasVegetation);
        StringBuilder legendLine = new StringBuilder();
        for (int i = 0; i < legend.size(); i++) {
            if (i > 0) legendLine.append("   ");
            legendLine.append(legend.get(i).symbol()).append(' ').append(legend.get(i).label());
        }

        FlatZone zone = largestFlatZone(heights, med, 1, classes);
        String zoneLine = "Largest flat zone: none found.";
        if (zone != null) {
            int zx1 = x1 + zone.col1() * step;
            int zx2 = Math.min(x2, x1 + (zone.col2() + 1) * step - 1);
            int zz1 = z1 + zone.row1() * step;
            int zz2 = Math.min(z2, z1 + (zone.row2() + 1) * step - 1);
            int zoneWidth = zx2 - zx1 + 1;
            int zoneDepth = zz2 - zz1 + 1;
            zoneLine = "Largest flat zone (+/-1 block): " + zoneWidth + "x" + zoneDepth + " at x=" + zx1 + ".." + zx2
                    + " z=" + zz1 + ".." + zz2 + ", y=" + med + ".";
        }

        List<Map.Entry<String, Long>> surfaceEntries = new ArrayList<>(input.surface().entrySet());
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
                sb.append(shortBlockName(e.getKey())).append(' ').append(pct).append('%');
            }
            surfaceText = sb.toString();
        } else {
            surfaceText = "unknown";
        }

        String summary = "Area x=[" + x1 + ".." + x2 + "] z=[" + z1 + ".." + z2 + "] (" + width + "x" + depth + "). "
                + "Surface y: min " + min + ", max " + max + ", median " + med + ". Surface: " + surfaceText + ". " + zoneLine;

        List<String> downsampleNote = new ArrayList<>();
        if (step > 1) {
            downsampleNote.add("Downsampled: 1 char = " + step + "x" + step + " blocks (median height per cell).");
            downsampleNote.add("");
        }

        boolean includeMatrix = input.matrix() != null ? input.matrix() : (long) width * depth <= MATRIX_AUTO_CELLS;
        List<String> matrixLines = includeMatrix
                ? renderMatrix(heights, x1, z1, step)
                : List.of("Height matrix omitted for this " + width + "x" + depth + " area (" + (width * depth) + " cells); "
                    + "pass matrix: true if you need exact per-block heights, or survey a smaller area.");

        List<String> all = new ArrayList<>();
        all.add(summary);
        all.add("");
        all.addAll(downsampleNote);
        all.addAll(mapLines);
        all.add("");
        all.add("Legend: " + legendLine);
        all.add("");
        all.addAll(matrixLines);
        return String.join("\n", all);
    }
}
