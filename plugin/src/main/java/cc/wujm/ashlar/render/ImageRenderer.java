// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Pure-Java (no Bukkit imports, so it is unit-testable without a server)
 * projection of a {@link RegionData} + resolved map colors into a pixel
 * image, for the {@code render} RPC (docs/prompts/step4e-prompt.md). Never
 * touches AWT/{@code Graphics2D} - every pixel, grid line and label is a
 * direct write into an {@code int[]} ARGB buffer, since servers commonly
 * have no fonts installed.
 *
 * <p>Callers (the Bukkit-aware side) resolve each {@link RegionData#palette()}
 * entry to an ARGB int first (map color, or {@code 0} for "transparent": air
 * or any block with no assigned map color - see {@code MapColorResolver}),
 * then hand both to {@link #render}. The returned {@link Output#pixels()} is
 * ready for {@code BufferedImage.setRGB}/{@code ImageIO.write}, done by the
 * caller off the main thread.
 */
public final class ImageRenderer {

    private ImageRenderer() {
    }

    /** One legend row: a palette entry's block id, its map-color hex, and how many rendered pixels used it. */
    public record LegendEntry(String block, String colorHex, long pixels) {
    }

    /** Everything {@code RenderHandler} needs to build the {@code render} RPC's JSON result. */
    public record Output(int width, int height, int scale, String axisRight, String axisDown,
                          int topLeftA, int topLeftB, int grid, List<LegendEntry> legend, int[] pixels) {
    }

    /** Per-cell palette index ({@code -1} = transparent) and shade (0-100, applied to the resolved ARGB), pre-upscale. */
    private record BaseImage(int blocksWide, int blocksTall, int[] cellIndex, int[] cellShade,
                              int[] colWorld, int[] rowWorld, String axisRight, String axisDown,
                              int topLeftA, int topLeftB) {
    }

    private static final long PIXEL_BUDGET = 4_000_000L;
    private static final int CHECKER_A = 0xFF3A3A3A;
    private static final int CHECKER_B = 0xFF4A4A4A;
    private static final double GRID_ALPHA = 0.4;

    /**
     * Renders one region as a 2D image.
     *
     * @param sliceAxis "x"/"y"/"z", only read when {@code view} is {@code "slice"}
     * @param sliceAt   the fixed coordinate on {@code sliceAxis}, only read when {@code view} is {@code "slice"}
     * @param scale     px per block, {@code <= 0} means auto (largest scale with {@code max(w,h) <= 1024} and
     *                  {@code w*h <= 4,000,000}); the halving-retry-on-size loop (docs/prompts/step4e-prompt.md,
     *                  Result section) calls back in with an explicit scale
     * @param grid      grid line every N blocks, {@code 0} disables grid lines and coordinate labels
     */
    public static Output render(RegionData data, int[] paletteArgb, String view, String sliceAxis, int sliceAt,
                                 int scale, int grid) {
        Region r = data.region();
        int minX = r.minX(), minY = r.minY(), minZ = r.minZ();
        int maxX = r.maxX(), maxY = r.maxY(), maxZ = r.maxZ();
        int dx = maxX - minX + 1, dy = maxY - minY + 1, dz = maxZ - minZ + 1;
        String v = view.toLowerCase(Locale.ROOT);

        if (v.equals("top")) {
            // Decode into the same (colorArgb, blockNames, topY) per-column shape TopViewTask produces from its
            // budgeted column scan (docs/prompts/step4g-prompt.md, Bug 2), then share the exact same core with it -
            // this path exists so pre-existing RegionData-based callers/tests keep working unchanged.
            int[] flat = decodeFlat(data);
            int blocksWide = dx, blocksTall = dz;
            int[] colorArgb = new int[blocksWide * blocksTall];
            String[] blockNames = new String[blocksWide * blocksTall];
            int[] topY = new int[blocksWide * blocksTall];
            Arrays.fill(topY, Integer.MIN_VALUE);
            for (int col = 0; col < blocksWide; col++) {
                int x = minX + col;
                for (int row = 0; row < blocksTall; row++) {
                    int z = minZ + row;
                    for (int y = maxY; y >= minY; y--) {
                        int p = flat[flatIndex(minX, minY, minZ, dx, dz, x, y, z)];
                        if (paletteArgb[p] != 0) {
                            int cell = row * blocksWide + col;
                            colorArgb[cell] = paletteArgb[p];
                            blockNames[cell] = data.palette().get(p);
                            topY[cell] = y;
                            break;
                        }
                    }
                }
            }
            return renderTop(colorArgb, blockNames, topY, blocksWide, blocksTall, minX, minZ, scale, grid);
        }

        int[] flat = decodeFlat(data);
        BaseImage base = buildBaseImage(flat, paletteArgb, v, sliceAxis, sliceAt,
                minX, minY, minZ, maxX, maxY, maxZ, dx, dy, dz);

        int effScale = resolveScale(scale, base.blocksWide(), base.blocksTall());
        int width = base.blocksWide() * effScale;
        int height = base.blocksTall() * effScale;
        int[] pixels = new int[width * height];

        for (int row = 0; row < base.blocksTall(); row++) {
            for (int col = 0; col < base.blocksWide(); col++) {
                int cell = row * base.blocksWide() + col;
                int idx = base.cellIndex()[cell];
                int color = idx < 0
                        ? (((col + row) & 1) == 0 ? CHECKER_A : CHECKER_B)
                        : applyShade(paletteArgb[idx], base.cellShade()[cell]);
                fillBlock(pixels, width, col * effScale, row * effScale, effScale, color);
            }
        }

        if (grid > 0) {
            drawGridLines(pixels, width, height, base.blocksWide(), base.blocksTall(), base.colWorld(), base.rowWorld(), effScale, grid);
            drawLabels(pixels, width, height, base.blocksWide(), base.blocksTall(), base.colWorld(), base.rowWorld(), effScale, grid);
        }

        List<LegendEntry> legend = buildLegend(data.palette(), paletteArgb, base, effScale);

        return new Output(width, height, effScale, base.axisRight(), base.axisDown(),
                base.topLeftA(), base.topLeftB(), grid, legend, pixels);
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /** Expands {@code data}'s runs into a flat palette-index array, y-outer/z-middle/x-inner (matches RegionData's encoding order). */
    private static int[] decodeFlat(RegionData data) {
        long volume = data.region().volume();
        int[] flat = new int[(int) volume];
        int[] runIndex = data.runIndex();
        int[] runLength = data.runLength();
        int pos = 0;
        for (int i = 0; i < runIndex.length; i++) {
            int len = runLength[i];
            Arrays.fill(flat, pos, pos + len, runIndex[i]);
            pos += len;
        }
        return flat;
    }

    private static int flatIndex(int minX, int minY, int minZ, int dx, int dz, int x, int y, int z) {
        return ((y - minY) * dz + (z - minZ)) * dx + (x - minX);
    }

    // ------------------------------------------------------------------
    // Base image construction (view projection + shading)
    // ------------------------------------------------------------------

    private static BaseImage buildBaseImage(int[] flat, int[] paletteArgb, String view, String sliceAxis, int sliceAt,
                                             int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                             int dx, int dy, int dz) {
        return switch (view) {
            case "south" -> scanSide(flat, paletteArgb, minX, minY, minZ, dx, dy, dz, dx, dy,
                    col -> minX + col, true, maxZ, minZ, -1, "+x (east)", "-y (down)", minX);
            case "north" -> scanSide(flat, paletteArgb, minX, minY, minZ, dx, dy, dz, dx, dy,
                    col -> maxX - col, true, minZ, maxZ, 1, "-x (west)", "-y (down)", maxX);
            case "east" -> scanSide(flat, paletteArgb, minX, minY, minZ, dx, dy, dz, dz, dy,
                    col -> maxZ - col, false, maxX, minX, -1, "-z (north)", "-y (down)", maxZ);
            case "west" -> scanSide(flat, paletteArgb, minX, minY, minZ, dx, dy, dz, dz, dy,
                    col -> minZ + col, false, minX, maxX, 1, "+z (south)", "-y (down)", minZ);
            case "slice" -> buildSlice(flat, paletteArgb, sliceAxis.toLowerCase(Locale.ROOT), sliceAt,
                    minX, minY, minZ, maxX, maxY, dx, dy, dz);
            default -> throw new IllegalArgumentException("unknown view: " + view);
        };
    }

    /**
     * Top-down view, shared core: paints one already-resolved color per {@code (x,z)} column, shaded like a
     * vanilla map against the column to the north (z-1). Used both by {@link #render} (which first decodes a
     * full {@link RegionData} into these same per-column arrays, docs/prompts/step4e-prompt.md) and directly by
     * {@link cc.wujm.ashlar.engine.TopViewTask} (which reads only one block per column via a budgeted
     * scan instead of the whole volume, docs/prompts/step4g-prompt.md Bug 2) - both paths produce byte-identical
     * images for the same terrain.
     *
     * @param colorArgb  [row*blocksWide+col] resolved ARGB per column; {@code 0} means "no block" (transparent/checkered)
     * @param blockNames [row*blocksWide+col] block-state string per column, for the legend; unused where {@code colorArgb} is {@code 0}
     * @param topY       [row*blocksWide+col] world y of the recorded block, for north-neighbour shading; unused where {@code colorArgb} is {@code 0}
     */
    public static Output renderTop(int[] colorArgb, String[] blockNames, int[] topY, int blocksWide, int blocksTall,
                                    int minX, int minZ, int scale, int grid) {
        int[] cellShade = new int[blocksWide * blocksTall];
        for (int col = 0; col < blocksWide; col++) {
            for (int row = 0; row < blocksTall; row++) {
                int cell = row * blocksWide + col;
                if (colorArgb[cell] == 0) {
                    continue;
                }
                if (row == 0) {
                    cellShade[cell] = 86; // top row: no column to the north, spec says treat as "equal"
                    continue;
                }
                int northCell = (row - 1) * blocksWide + col;
                if (colorArgb[northCell] == 0) {
                    cellShade[cell] = 86; // north column empty: no comparison possible, fall back to "equal"
                    continue;
                }
                int cur = topY[cell], north = topY[northCell];
                cellShade[cell] = cur > north ? 100 : (cur == north ? 86 : 71);
            }
        }

        int effScale = resolveScale(scale, blocksWide, blocksTall);
        int width = blocksWide * effScale;
        int height = blocksTall * effScale;
        int[] pixels = new int[width * height];
        for (int row = 0; row < blocksTall; row++) {
            for (int col = 0; col < blocksWide; col++) {
                int cell = row * blocksWide + col;
                int color = colorArgb[cell] == 0
                        ? (((col + row) & 1) == 0 ? CHECKER_A : CHECKER_B)
                        : applyShade(colorArgb[cell], cellShade[cell]);
                fillBlock(pixels, width, col * effScale, row * effScale, effScale, color);
            }
        }

        if (grid > 0) {
            int[] colWorld = new int[blocksWide];
            for (int c = 0; c < blocksWide; c++) colWorld[c] = minX + c;
            int[] rowWorld = new int[blocksTall];
            for (int rr = 0; rr < blocksTall; rr++) rowWorld[rr] = minZ + rr;
            drawGridLines(pixels, width, height, blocksWide, blocksTall, colWorld, rowWorld, effScale, grid);
            drawLabels(pixels, width, height, blocksWide, blocksTall, colWorld, rowWorld, effScale, grid);
        }

        List<LegendEntry> legend = buildTopLegend(colorArgb, blockNames, effScale);
        return new Output(width, height, effScale, "+x (east)", "+z (south)", minX, minZ, grid, legend, pixels);
    }

    /** Legend for {@link #renderTop}: grouped by block-state string (which maps 1:1 to a color), not a palette index. */
    private static List<LegendEntry> buildTopLegend(int[] colorArgb, String[] blockNames, int scale) {
        Map<String, Long> counts = new HashMap<>();
        Map<String, Integer> colorByName = new HashMap<>();
        for (int i = 0; i < colorArgb.length; i++) {
            if (colorArgb[i] == 0) {
                continue;
            }
            String name = blockNames[i];
            counts.merge(name, 1L, Long::sum);
            colorByName.putIfAbsent(name, colorArgb[i]);
        }
        long perCell = (long) scale * scale;
        List<LegendEntry> list = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            list.add(new LegendEntry(e.getKey(), toHex(colorByName.get(e.getKey())), e.getValue() * perCell));
        }
        list.sort((a, b) -> Long.compare(b.pixels(), a.pixels()));
        return list.size() > 12 ? list.subList(0, 12) : list;
    }

    /**
     * One side elevation (north/south/east/west). {@code colToFixed} gives the fixed horizontal-axis coordinate
     * for a column (x for north/south, z for east/west; {@code colIsX} says which); the depth axis is scanned
     * from {@code scanStart} to {@code scanEnd} (inclusive, stepping by {@code scanStep}) looking for the first
     * non-transparent cell, which is shaded by how many steps back it was found (spec: -3% per block, floor 60%).
     */
    private static BaseImage scanSide(int[] flat, int[] paletteArgb,
                                       int minX, int minY, int minZ, int dx, int dy, int dz,
                                       int blocksWide, int blocksTall,
                                       IntUnaryOperator colToFixed, boolean colIsX,
                                       int scanStart, int scanEnd, int scanStep,
                                       String axisRight, String axisDown, int topLeftA) {
        int maxY = minY + dy - 1;
        int[] cellIndex = new int[blocksWide * blocksTall];
        int[] cellShade = new int[blocksWide * blocksTall];
        Arrays.fill(cellIndex, -1);

        for (int col = 0; col < blocksWide; col++) {
            int fixed = colToFixed.applyAsInt(col);
            for (int row = 0; row < blocksTall; row++) {
                int y = maxY - row;
                int cell = row * blocksWide + col;
                int depth = 0;
                for (int s = scanStart; ; s += scanStep) {
                    int x = colIsX ? fixed : s;
                    int z = colIsX ? s : fixed;
                    int p = flat[flatIndex(minX, minY, minZ, dx, dz, x, y, z)];
                    if (paletteArgb[p] != 0) {
                        cellIndex[cell] = p;
                        cellShade[cell] = Math.max(60, 100 - 3 * depth);
                        break;
                    }
                    if (s == scanEnd) {
                        break;
                    }
                    depth++;
                }
            }
        }

        int[] colWorld = new int[blocksWide];
        for (int c = 0; c < blocksWide; c++) colWorld[c] = colToFixed.applyAsInt(c);
        int[] rowWorld = new int[blocksTall];
        for (int rIdx = 0; rIdx < blocksTall; rIdx++) rowWorld[rIdx] = maxY - rIdx;
        return new BaseImage(blocksWide, blocksTall, cellIndex, cellShade, colWorld, rowWorld,
                axisRight, axisDown, topLeftA, maxY);
    }

    /** A single exact-color 2D cross-section; transparent for transparent cells, no shading. */
    private static BaseImage buildSlice(int[] flat, int[] paletteArgb, String axis, int at,
                                         int minX, int minY, int minZ, int maxX, int maxY,
                                         int dx, int dy, int dz) {
        return switch (axis) {
            case "y" -> {
                int blocksWide = dx, blocksTall = dz;
                int[] cellIndex = new int[blocksWide * blocksTall];
                int[] cellShade = new int[blocksWide * blocksTall];
                for (int col = 0; col < blocksWide; col++) {
                    int x = minX + col;
                    for (int row = 0; row < blocksTall; row++) {
                        int z = minZ + row;
                        int p = flat[flatIndex(minX, minY, minZ, dx, dz, x, at, z)];
                        int cell = row * blocksWide + col;
                        if (paletteArgb[p] != 0) {
                            cellIndex[cell] = p;
                            cellShade[cell] = 100;
                        } else {
                            cellIndex[cell] = -1;
                        }
                    }
                }
                int[] colWorld = new int[blocksWide];
                for (int c = 0; c < blocksWide; c++) colWorld[c] = minX + c;
                int[] rowWorld = new int[blocksTall];
                for (int rr = 0; rr < blocksTall; rr++) rowWorld[rr] = minZ + rr;
                yield new BaseImage(blocksWide, blocksTall, cellIndex, cellShade, colWorld, rowWorld,
                        "+x (east)", "+z (south)", minX, minZ);
            }
            case "z" -> {
                int blocksWide = dx, blocksTall = dy;
                int[] cellIndex = new int[blocksWide * blocksTall];
                int[] cellShade = new int[blocksWide * blocksTall];
                for (int col = 0; col < blocksWide; col++) {
                    int x = minX + col;
                    for (int row = 0; row < blocksTall; row++) {
                        int y = maxY - row;
                        int p = flat[flatIndex(minX, minY, minZ, dx, dz, x, y, at)];
                        int cell = row * blocksWide + col;
                        if (paletteArgb[p] != 0) {
                            cellIndex[cell] = p;
                            cellShade[cell] = 100;
                        } else {
                            cellIndex[cell] = -1;
                        }
                    }
                }
                int[] colWorld = new int[blocksWide];
                for (int c = 0; c < blocksWide; c++) colWorld[c] = minX + c;
                int[] rowWorld = new int[blocksTall];
                for (int rr = 0; rr < blocksTall; rr++) rowWorld[rr] = maxY - rr;
                yield new BaseImage(blocksWide, blocksTall, cellIndex, cellShade, colWorld, rowWorld,
                        "+x (east)", "-y (down)", minX, maxY);
            }
            case "x" -> {
                int blocksWide = dz, blocksTall = dy;
                int[] cellIndex = new int[blocksWide * blocksTall];
                int[] cellShade = new int[blocksWide * blocksTall];
                for (int col = 0; col < blocksWide; col++) {
                    int z = minZ + col;
                    for (int row = 0; row < blocksTall; row++) {
                        int y = maxY - row;
                        int p = flat[flatIndex(minX, minY, minZ, dx, dz, at, y, z)];
                        int cell = row * blocksWide + col;
                        if (paletteArgb[p] != 0) {
                            cellIndex[cell] = p;
                            cellShade[cell] = 100;
                        } else {
                            cellIndex[cell] = -1;
                        }
                    }
                }
                int[] colWorld = new int[blocksWide];
                for (int c = 0; c < blocksWide; c++) colWorld[c] = minZ + c;
                int[] rowWorld = new int[blocksTall];
                for (int rr = 0; rr < blocksTall; rr++) rowWorld[rr] = maxY - rr;
                yield new BaseImage(blocksWide, blocksTall, cellIndex, cellShade, colWorld, rowWorld,
                        "+z (south)", "-y (down)", minZ, maxY);
            }
            default -> throw new IllegalArgumentException("unknown slice axis: " + axis);
        };
    }

    // ------------------------------------------------------------------
    // Scale / upscale
    // ------------------------------------------------------------------

    /** Package-visible: reused by {@link HeightmapImageRenderer}, which shares the same auto-scale/pixel-budget rule. */
    static int resolveScale(int requested, int blocksWide, int blocksTall) {
        if (requested > 0) {
            return requested;
        }
        int maxDim = Math.max(blocksWide, blocksTall);
        int auto = Math.max(1, 1024 / Math.max(1, maxDim));
        while (auto > 1 && (long) blocksWide * auto * (long) blocksTall * auto > PIXEL_BUDGET) {
            auto--;
        }
        return Math.max(1, auto);
    }

    /** Package-visible: reused by {@link HeightmapImageRenderer}. */
    static void fillBlock(int[] pixels, int width, int px, int py, int size, int color) {
        for (int y = 0; y < size; y++) {
            int rowStart = (py + y) * width + px;
            Arrays.fill(pixels, rowStart, rowStart + size, color);
        }
    }

    /** Package-visible: reused by {@link HeightmapImageRenderer} for its own shading and liquid-depth darkening. */
    static int applyShade(int argb, int pct) {
        int a = (argb >>> 24) & 0xFF;
        int rr = Math.min(255, ((argb >> 16) & 0xFF) * pct / 100);
        int gg = Math.min(255, ((argb >> 8) & 0xFF) * pct / 100);
        int bb = Math.min(255, (argb & 0xFF) * pct / 100);
        return (a << 24) | (rr << 16) | (gg << 8) | bb;
    }

    // ------------------------------------------------------------------
    // Grid lines
    // ------------------------------------------------------------------

    /** Package-visible (plain arrays, not the private {@code BaseImage} record): reused by {@link HeightmapImageRenderer}. */
    static void drawGridLines(int[] pixels, int width, int height, int blocksWide, int blocksTall,
                               int[] colWorld, int[] rowWorld, int scale, int grid) {
        int major = grid * 5;
        for (int col = 0; col < blocksWide; col++) {
            int w = colWorld[col];
            if (Math.floorMod(w, grid) != 0) {
                continue;
            }
            int thickness = Math.floorMod(w, major) == 0 ? 2 : 1;
            blendVerticalLine(pixels, width, height, col * scale, thickness);
        }
        for (int row = 0; row < blocksTall; row++) {
            int w = rowWorld[row];
            if (Math.floorMod(w, grid) != 0) {
                continue;
            }
            int thickness = Math.floorMod(w, major) == 0 ? 2 : 1;
            blendHorizontalLine(pixels, width, height, row * scale, thickness);
        }
    }

    private static void blendVerticalLine(int[] pixels, int width, int height, int px, int thickness) {
        for (int t = 0; t < thickness; t++) {
            int x = px + t;
            if (x < 0 || x >= width) {
                continue;
            }
            for (int y = 0; y < height; y++) {
                int i = y * width + x;
                pixels[i] = blend(pixels[i]);
            }
        }
    }

    private static void blendHorizontalLine(int[] pixels, int width, int height, int py, int thickness) {
        for (int t = 0; t < thickness; t++) {
            int y = py + t;
            if (y < 0 || y >= height) {
                continue;
            }
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                pixels[rowStart + x] = blend(pixels[rowStart + x]);
            }
        }
    }

    /**
     * Blends {@code GRID_ALPHA} opaque black over {@code base}, keeping {@code base}'s alpha channel.
     * Package-visible: {@link HeightmapImageRenderer} reuses this exact blend for its contour lines too,
     * so contour and grid lines read as the same visual weight.
     */
    static int blend(int base) {
        int a = (base >>> 24) & 0xFF;
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int rr = (int) Math.round(br * (1 - GRID_ALPHA));
        int gg = (int) Math.round(bg * (1 - GRID_ALPHA));
        int bbl = (int) Math.round(bb * (1 - GRID_ALPHA));
        return (a << 24) | (rr << 16) | (gg << 8) | bbl;
    }

    // ------------------------------------------------------------------
    // Coordinate labels (built-in 3x5 bitmap font, no AWT/Graphics2D)
    // ------------------------------------------------------------------

    private static final Map<Character, String[]> FONT = buildFont();

    private static Map<Character, String[]> buildFont() {
        Map<Character, String[]> m = new HashMap<>();
        m.put('0', new String[]{"111", "101", "101", "101", "111"});
        m.put('1', new String[]{"010", "110", "010", "010", "111"});
        m.put('2', new String[]{"111", "001", "111", "100", "111"});
        m.put('3', new String[]{"111", "001", "111", "001", "111"});
        m.put('4', new String[]{"101", "101", "111", "001", "001"});
        m.put('5', new String[]{"111", "100", "111", "001", "111"});
        m.put('6', new String[]{"111", "100", "111", "101", "111"});
        m.put('7', new String[]{"111", "001", "001", "001", "001"});
        m.put('8', new String[]{"111", "101", "111", "101", "111"});
        m.put('9', new String[]{"111", "101", "111", "001", "111"});
        m.put('-', new String[]{"000", "000", "111", "000", "000"});
        return m;
    }

    /** Package-visible (plain arrays, not the private {@code BaseImage} record): reused by {@link HeightmapImageRenderer}. */
    static void drawLabels(int[] pixels, int width, int height, int blocksWide, int blocksTall,
                            int[] colWorld, int[] rowWorld, int scale, int grid) {
        int pxSize = scale <= 2 ? 1 : 2;

        int lastRight = Integer.MIN_VALUE;
        for (int col = 0; col < blocksWide; col++) {
            int w = colWorld[col];
            if (Math.floorMod(w, grid) != 0) {
                continue;
            }
            String text = String.valueOf(w);
            int x0 = col * scale + 1;
            int labelWidth = labelPixelWidth(text, pxSize);
            int labelHeight = 5 * pxSize;
            if (x0 <= lastRight || x0 + labelWidth > width || 1 + labelHeight > height) {
                continue;
            }
            drawLabel(pixels, width, height, x0, 1, text, pxSize);
            lastRight = x0 + labelWidth;
        }

        int lastBottom = Integer.MIN_VALUE;
        for (int row = 0; row < blocksTall; row++) {
            int w = rowWorld[row];
            if (Math.floorMod(w, grid) != 0) {
                continue;
            }
            String text = String.valueOf(w);
            int y0 = row * scale + 1;
            int labelWidth = labelPixelWidth(text, pxSize);
            int labelHeight = 5 * pxSize;
            if (y0 <= lastBottom || y0 + labelHeight > height || 1 + labelWidth > width) {
                continue;
            }
            drawLabel(pixels, width, height, 1, y0, text, pxSize);
            lastBottom = y0 + labelHeight;
        }
    }

    private static int labelPixelWidth(String text, int pxSize) {
        return text.length() * 3 * pxSize + (text.length() - 1) * pxSize;
    }

    private static void drawLabel(int[] pixels, int width, int height, int x0, int y0, String text, int pxSize) {
        int cursor = x0;
        for (int i = 0; i < text.length(); i++) {
            drawGlyph(pixels, width, height, cursor, y0, text.charAt(i), pxSize);
            cursor += 3 * pxSize + pxSize;
        }
    }

    private static void drawGlyph(int[] pixels, int width, int height, int x0, int y0, char ch, int pxSize) {
        String[] glyph = FONT.get(ch);
        if (glyph == null) {
            return;
        }
        // Outline pass first (1 raw px of black around every "on" cell), then the white fill on top.
        for (int gy = 0; gy < 5; gy++) {
            for (int gx = 0; gx < 3; gx++) {
                if (glyph[gy].charAt(gx) != '1') {
                    continue;
                }
                int px = x0 + gx * pxSize, py = y0 + gy * pxSize;
                for (int ddy = -1; ddy <= 1; ddy++) {
                    for (int ddx = -1; ddx <= 1; ddx++) {
                        if (ddx == 0 && ddy == 0) {
                            continue;
                        }
                        fillBlockSafe(pixels, width, height, px + ddx, py + ddy, pxSize, 0xFF000000);
                    }
                }
            }
        }
        for (int gy = 0; gy < 5; gy++) {
            for (int gx = 0; gx < 3; gx++) {
                if (glyph[gy].charAt(gx) != '1') {
                    continue;
                }
                int px = x0 + gx * pxSize, py = y0 + gy * pxSize;
                fillBlockSafe(pixels, width, height, px, py, pxSize, 0xFFFFFFFF);
            }
        }
    }

    private static void fillBlockSafe(int[] pixels, int width, int height, int px, int py, int size, int color) {
        for (int y = 0; y < size; y++) {
            int yy = py + y;
            if (yy < 0 || yy >= height) {
                continue;
            }
            int rowStart = yy * width;
            for (int x = 0; x < size; x++) {
                int xx = px + x;
                if (xx < 0 || xx >= width) {
                    continue;
                }
                pixels[rowStart + xx] = color;
            }
        }
    }

    /** Test-only entry point for the bitmap font, independent of the full {@link #render} pipeline. */
    public static int[] renderLabelForTest(String text, int pxSize, int canvasWidth, int canvasHeight) {
        int[] pixels = new int[canvasWidth * canvasHeight];
        drawLabel(pixels, canvasWidth, canvasHeight, 0, 0, text, pxSize);
        return pixels;
    }

    // ------------------------------------------------------------------
    // Legend
    // ------------------------------------------------------------------

    private static List<LegendEntry> buildLegend(List<String> palette, int[] paletteArgb, BaseImage base, int scale) {
        Map<Integer, Long> counts = new HashMap<>();
        for (int idx : base.cellIndex()) {
            if (idx >= 0) {
                counts.merge(idx, 1L, Long::sum);
            }
        }
        long perCell = (long) scale * scale;
        List<LegendEntry> list = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : counts.entrySet()) {
            int idx = e.getKey();
            list.add(new LegendEntry(palette.get(idx), toHex(paletteArgb[idx]), e.getValue() * perCell));
        }
        list.sort((a, b) -> Long.compare(b.pixels(), a.pixels()));
        return list.size() > 12 ? list.subList(0, 12) : list;
    }

    /** Package-visible: reused by {@link HeightmapImageRenderer} for its band/legend hex colors. */
    static String toHex(int argb) {
        return String.format(Locale.ROOT, "#%06x", argb & 0xFFFFFF);
    }
}
