// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import cc.wujm.ashlar.engine.SurfaceClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-Java (no Bukkit imports, unit-testable without a server) renderer for
 * the {@code render} RPC's {@code view: "heightmap"} (docs/prompts/step4f-prompt.md,
 * updated by docs/prompts/step4g-prompt.md Bug 1). Paints a hypsometric-tinted
 * top-down height map: 8 color bands from low to high, blue for liquid cells
 * (darker for deep water/lava), a dark olive flat color for vegetation cells
 * (trees etc, never banded by height - a trunk-top height would otherwise
 * skew the band scale), the same north-neighbour relief shading {@link
 * ImageRenderer} uses for its own {@code "top"} view, and optional contour
 * lines. Grid lines, coordinate labels and the auto-scale/pixel-budget rule
 * are the exact same code {@link ImageRenderer} uses for every other view
 * (package-visible methods reused directly, not copied).
 *
 * <p>Callers (the Bukkit-aware side, {@code RenderHandler}) supply the
 * already-read {@code heights}/{@code classes}/{@code liquidDepth} grids -
 * this class never touches the world. {@code classes} holds {@link
 * SurfaceClass#code()} per cell (0 ground, 1 liquid, 2 vegetation).
 * {@link #largestFlatZone} and {@link #median} are exposed here too (rather
 * than duplicated in the handler) so the "largest flat rectangle" rule stays
 * covered by the same pure-Java unit tests as the rendering itself; both
 * exclude vegetation cells, same as the height-band min/max computed inside
 * {@link #render}.
 */
public final class HeightmapImageRenderer {

    private HeightmapImageRenderer() {
    }

    /** One legend row: a band's color and the height range (or {@code "water"}) it represents. */
    public record Band(String colorHex, String label) {
    }

    /** Everything {@code RenderHandler} needs to build the heightmap {@code render} RPC's JSON result. */
    public record Output(int width, int height, int scale, int grid, int contour, List<Band> legend, int[] pixels) {
    }

    /** The largest axis-aligned rectangle within {@code tolerance} blocks of the median height, in world coordinates. */
    public record FlatZone(int x1, int z1, int x2, int z2, int y, int width, int depth) {
    }

    private static final int LIQUID_ARGB = 0xFF3F76E4;
    private static final int LIQUID_DEEP_SHADE_PCT = 65; // applied via ImageRenderer.applyShade for depth >= 3
    private static final int LIQUID_DEEP_THRESHOLD = 3;
    private static final int VEGETATION_ARGB = 0xFF3B5323;

    /** 8 hypsometric bands, low to high: dark green -> green -> light green -> yellow-green -> yellow -> tan -> brown -> light gray. */
    private static final int[] BAND_COLORS = {
            0xFF1B4D2E,
            0xFF2F7A3D,
            0xFF4FA64F,
            0xFF8EC63F,
            0xFFD9C93F,
            0xFFC9A04A,
            0xFFA97A4A,
            0xFFD9D9D9
    };

    /**
     * Renders one heightmap area as a 2D image.
     *
     * @param heights     [row=z-minZ][col=x-minX] surface height of the requested heightmap type (trunk top for vegetation cells)
     * @param classes     same shape: {@link SurfaceClass#code()} per cell (0 ground, 1 liquid, 2 vegetation)
     * @param liquidDepth same shape: liquid depth, only meaningful where {@code classes} is liquid
     * @param scale       px per block, {@code <= 0} means auto (same rule as {@link ImageRenderer})
     * @param grid        grid line every N blocks, {@code 0} disables grid lines and coordinate labels
     * @param contour     draw a 1px line between adjacent cells whose {@code floor(h/contour)} differs, {@code 0} disables
     */
    public static Output render(int[][] heights, int[][] classes, int[][] liquidDepth,
                                 int minX, int minZ, int scale, int grid, int contour) {
        int blocksTall = heights.length;
        int blocksWide = blocksTall > 0 ? heights[0].length : 0;

        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int row = 0; row < blocksTall; row++) {
            for (int col = 0; col < blocksWide; col++) {
                if (classes[row][col] == SurfaceClass.VEGETATION.code()) {
                    continue; // Bug 1: trunk-top heights must not skew the band scale
                }
                int h = heights[row][col];
                if (h < min) min = h;
                if (h > max) max = h;
            }
        }
        if (blocksWide == 0 || blocksTall == 0 || min > max) {
            // Either an empty grid, or (pathological) every cell is vegetation: fall back to the raw range.
            min = 0;
            max = 0;
            for (int[] row : heights) {
                for (int h : row) {
                    if (h < min) min = h;
                    if (h > max) max = h;
                }
            }
        }

        int effScale = ImageRenderer.resolveScale(scale, blocksWide, blocksTall);
        int width = blocksWide * effScale;
        int height = blocksTall * effScale;
        int[] pixels = new int[width * height];

        boolean hasLiquid = false;
        boolean hasVegetation = false;
        for (int row = 0; row < blocksTall; row++) {
            for (int col = 0; col < blocksWide; col++) {
                int cls = classes[row][col];
                boolean isLiquid = cls == SurfaceClass.LIQUID.code();
                boolean isVegetation = cls == SurfaceClass.VEGETATION.code();
                hasLiquid |= isLiquid;
                hasVegetation |= isVegetation;
                int base;
                if (isLiquid) {
                    int depth = liquidDepth[row][col];
                    base = depth >= LIQUID_DEEP_THRESHOLD ? ImageRenderer.applyShade(LIQUID_ARGB, LIQUID_DEEP_SHADE_PCT) : LIQUID_ARGB;
                } else if (isVegetation) {
                    base = VEGETATION_ARGB;
                } else {
                    base = BAND_COLORS[bandIndex(heights[row][col], min, max)];
                }
                int shadePct = shadeFor(row, col, heights);
                int color = ImageRenderer.applyShade(base, shadePct);
                ImageRenderer.fillBlock(pixels, width, col * effScale, row * effScale, effScale, color);
            }
        }

        if (contour > 0) {
            drawContourLines(pixels, width, height, heights, blocksWide, blocksTall, effScale, contour);
        }

        if (grid > 0) {
            int[] colWorld = new int[blocksWide];
            for (int c = 0; c < blocksWide; c++) colWorld[c] = minX + c;
            int[] rowWorld = new int[blocksTall];
            for (int r = 0; r < blocksTall; r++) rowWorld[r] = minZ + r;
            ImageRenderer.drawGridLines(pixels, width, height, blocksWide, blocksTall, colWorld, rowWorld, effScale, grid);
            ImageRenderer.drawLabels(pixels, width, height, blocksWide, blocksTall, colWorld, rowWorld, effScale, grid);
        }

        List<Band> legend = buildLegend(min, max, hasLiquid, hasVegetation);
        return new Output(width, height, effScale, grid, contour, legend, pixels);
    }

    private static int bandIndex(int h, int min, int max) {
        if (max == min) {
            return 0;
        }
        if (h >= max) {
            return 7;
        }
        double frac = (double) (h - min) / (max - min);
        int b = (int) Math.floor(frac * 8);
        return Math.min(7, Math.max(0, b));
    }

    /** North-neighbour relief shade, identical rule to {@link ImageRenderer}'s {@code "top"} view: 100/86/71%, row 0 forced 86%. */
    private static int shadeFor(int row, int col, int[][] heights) {
        if (row == 0) {
            return 86;
        }
        int cur = heights[row][col];
        int north = heights[row - 1][col];
        return cur > north ? 100 : (cur == north ? 86 : 71);
    }

    // ------------------------------------------------------------------
    // Contour lines
    // ------------------------------------------------------------------

    private static void drawContourLines(int[] pixels, int width, int height, int[][] heights,
                                          int blocksWide, int blocksTall, int scale, int contour) {
        for (int row = 0; row < blocksTall; row++) {
            for (int col = 0; col < blocksWide; col++) {
                int h = heights[row][col];
                if (col + 1 < blocksWide && Math.floorDiv(h, contour) != Math.floorDiv(heights[row][col + 1], contour)) {
                    int x = (col + 1) * scale;
                    if (x < width) {
                        int yEnd = Math.min(height, row * scale + scale);
                        for (int y = row * scale; y < yEnd; y++) {
                            int i = y * width + x;
                            pixels[i] = ImageRenderer.blend(pixels[i]);
                        }
                    }
                }
                if (row + 1 < blocksTall && Math.floorDiv(h, contour) != Math.floorDiv(heights[row + 1][col], contour)) {
                    int y = (row + 1) * scale;
                    if (y < height) {
                        int xEnd = Math.min(width, col * scale + scale);
                        int rowStart = y * width;
                        for (int x = col * scale; x < xEnd; x++) {
                            pixels[rowStart + x] = ImageRenderer.blend(pixels[rowStart + x]);
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Legend
    // ------------------------------------------------------------------

    private static List<Band> buildLegend(int min, int max, boolean hasLiquid, boolean hasVegetation) {
        List<Band> list = new ArrayList<>();
        if (hasLiquid) {
            list.add(new Band(ImageRenderer.toHex(LIQUID_ARGB), "water"));
        }
        if (hasVegetation) {
            list.add(new Band(ImageRenderer.toHex(VEGETATION_ARGB), "trees / vegetation"));
        }
        if (min == max) {
            list.add(new Band(ImageRenderer.toHex(BAND_COLORS[0]), "y " + min));
            return list;
        }
        int range = max - min;
        for (int b = 0; b < 8; b++) {
            int lo = min + (b * range) / 8;
            int hi = b == 7 ? max : min + ((b + 1) * range + 7) / 8 - 1;
            if (hi < lo) hi = lo;
            String label = lo == hi ? ("y " + lo) : ("y " + lo + "-" + hi);
            list.add(new Band(ImageRenderer.toHex(BAND_COLORS[b]), label));
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Stats: median height, largest flat zone (same rule as the MCP relief renderer's largestFlatZone)
    // ------------------------------------------------------------------

    public static int median(int[][] heights) {
        return median(heights, null);
    }

    /** Same as {@link #median(int[][])}, but excludes vegetation cells (falls back to all cells if every cell is vegetation). */
    public static int median(int[][] heights, int[][] classes) {
        int total = 0;
        for (int[] row : heights) total += row.length;
        int[] flat = new int[total];
        int i = 0;
        for (int r = 0; r < heights.length; r++) {
            for (int c = 0; c < heights[r].length; c++) {
                if (classes != null && classes[r][c] == SurfaceClass.VEGETATION.code()) {
                    continue;
                }
                flat[i++] = heights[r][c];
            }
        }
        if (i == 0) {
            // Every cell was vegetation: fall back to the raw set rather than reporting a bogus 0.
            i = 0;
            for (int[] row : heights) {
                for (int h : row) {
                    flat[i++] = h;
                }
            }
        }
        int[] used = i == flat.length ? flat : Arrays.copyOf(flat, i);
        Arrays.sort(used);
        return used.length == 0 ? 0 : used[used.length / 2];
    }

    /**
     * Largest axis-aligned rectangle of cells within {@code tolerance} blocks of {@code medianValue}
     * (same +/-1 rule as {@code mcp-server/src/render/relief.ts}'s {@code largestFlatZone}), via the
     * standard "largest rectangle in a binary matrix" histogram/stack algorithm, O(rows*cols). Vegetation
     * cells (per {@code classes}, may be {@code null} to consider every cell) are never flat.
     */
    public static FlatZone largestFlatZone(int[][] heights, int[][] classes, int minX, int minZ, int medianValue, int tolerance) {
        int rows = heights.length;
        if (rows == 0) return null;
        int cols = heights[0].length;
        if (cols == 0) return null;

        boolean[][] flat = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                boolean isVegetation = classes != null && classes[r][c] == SurfaceClass.VEGETATION.code();
                flat[r][c] = !isVegetation && Math.abs(heights[r][c] - medianValue) <= tolerance;
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
                    int barWidth = c - left;
                    int area = barHeight * barWidth;
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
        int x1 = minX + bestCol1, x2 = minX + bestCol2;
        int z1 = minZ + bestRow1, z2 = minZ + bestRow2;
        return new FlatZone(x1, z1, x2, z2, medianValue, x2 - x1 + 1, z2 - z1 + 1);
    }
}
