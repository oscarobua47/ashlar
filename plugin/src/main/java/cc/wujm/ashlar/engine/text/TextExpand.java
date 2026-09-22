// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@code mc_build} {@code text} entry into world-coordinate block runs (step8k-prompt.md
 * &sect;B): rasterizes {@link TextLayout}'s placement into a pixel grid, then walks each raster row
 * left to right, emitting one {@link Run} per horizontal run of consecutive ink pixels (not one per
 * pixel), scaled by {@code scale}. Pure - no Bukkit, no JSON - so it is directly unit-testable
 * (TextExpandTest) and reusable from {@code McBuild}'s network-thread validation.
 *
 * <p>Direction math (per facing, verified on the test server per step8k-prompt.md &sect;B - do not
 * trust this comment blindly if it is ever found to disagree with what {@code mc_render} shows):
 * reading direction (left-to-right for the viewer) and the "up" direction of the text -
 * <ul>
 * <li>{@code south}: advance +x, up +y, plane at fixed z = pos.z
 * <li>{@code north}: advance -x, up +y, plane at fixed z = pos.z
 * <li>{@code east}: advance -z, up +y, plane at fixed x = pos.x
 * <li>{@code west}: advance +z, up +y, plane at fixed x = pos.x
 * <li>{@code up}: advance +x, "up" of the text = -z (north), pixels lie in the y = pos.y plane
 * </ul>
 * {@code pos} is the block of the bottom-left pixel of the first (top) line of the text; later
 * lines extend further in the "down" direction (opposite of each facing's up axis).
 */
public final class TextExpand {

    public static final List<String> FACINGS = List.of("south", "north", "east", "west", "up");

    public static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 4;
    public static final int MIN_SPACING = 0;
    public static final int MAX_SPACING = 3;

    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;

    private TextExpand() {
    }

    /** One axis-aligned run of blocks: {@code from}=({@code x1,y1,z1}), {@code to}=({@code x2,y2,z2}), any corner order (matches {@code Region.of}). */
    public record Run(int x1, int y1, int z1, int x2, int y2, int z2) {
        public int[] from() {
            return new int[] {x1, y1, z1};
        }

        public int[] to() {
            return new int[] {x2, y2, z2};
        }
    }

    /**
     * @param inkRuns one run per horizontal pixel run (already scaled), the letter blocks
     * @param bboxMin the rendered text's exact bounding box, min corner (inclusive)
     * @param bboxMax the rendered text's exact bounding box, max corner (inclusive) - also the region
     *        a {@code background} fill should cover
     * @param widthBlocks the text's total width in blocks (pixel width * scale)
     * @param heightBlocks the text's total height in blocks, including line gaps (pixel height * scale)
     * @param inkBlockCount total number of letter blocks (sum of every run's length), before any background
     */
    public record Result(List<Run> inkRuns, int[] bboxMin, int[] bboxMax, int widthBlocks, int heightBlocks,
            long inkBlockCount) {
    }

    private record Axes(int advanceAxis, int advanceSign, int upAxis, int upSign, int depthAxis) {
    }

    private static Axes axesFor(String facing) {
        return switch (facing) {
            case "south" -> new Axes(AXIS_X, 1, AXIS_Y, 1, AXIS_Z);
            case "north" -> new Axes(AXIS_X, -1, AXIS_Y, 1, AXIS_Z);
            case "east" -> new Axes(AXIS_Z, -1, AXIS_Y, 1, AXIS_X);
            case "west" -> new Axes(AXIS_Z, 1, AXIS_Y, 1, AXIS_X);
            case "up" -> new Axes(AXIS_X, 1, AXIS_Z, -1, AXIS_Y);
            default -> throw new IllegalArgumentException(
                    "facing must be one of " + FACINGS + ", got '" + facing + "'");
        };
    }

    /**
     * @throws IllegalArgumentException on a bad facing/scale/spacing, or a layout error propagated
     *         from {@link TextLayout#layout}
     * @throws FontRenderException propagated from {@link Glyphs#glyphFor} when a non-ASCII character
     *         cannot be rendered on this JVM
     */
    public static Result expand(String text, int[] pos, String facing, int scale, int spacing) {
        if (!FACINGS.contains(facing)) {
            throw new IllegalArgumentException("facing must be one of " + FACINGS + ", got '" + facing + "'");
        }
        if (scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException("scale must be between " + MIN_SCALE + " and " + MAX_SCALE + ", got " + scale);
        }
        if (spacing < MIN_SPACING || spacing > MAX_SPACING) {
            throw new IllegalArgumentException("spacing must be between " + MIN_SPACING + " and " + MAX_SPACING + ", got " + spacing);
        }

        TextLayout.Layout layout = TextLayout.layout(text, spacing);
        int totalRows = layout.height();
        int totalCols = Math.max(1, layout.width());

        boolean[][] ink = new boolean[totalRows][totalCols];
        for (TextLayout.PlacedGlyph pg : layout.glyphs()) {
            Glyph g = Glyphs.glyphFor(pg.codePoint());
            for (int r = 0; r < g.height(); r++) {
                for (int c = 0; c < g.width(); c++) {
                    if (g.pixels()[r][c]) {
                        ink[pg.row() + r][pg.col() + c] = true;
                    }
                }
            }
        }
        // pos is the bottom-left block of the FIRST line: that line's bottom row has up-offset 0.
        int baseRow = layout.firstLineHeight() - 1;

        Axes axes = axesFor(facing);

        List<Run> runs = new ArrayList<>();
        long inkBlockCount = 0;
        for (int row = 0; row < totalRows; row++) {
            int col = 0;
            while (col < totalCols) {
                if (!ink[row][col]) {
                    col++;
                    continue;
                }
                int start = col;
                while (col < totalCols && ink[row][col]) {
                    col++;
                }
                int end = col - 1;
                runs.add(box(pos, axes, start, end, row, row, scale, baseRow));
                inkBlockCount += (long) (end - start + 1) * scale * scale;
            }
        }

        Run full = box(pos, axes, 0, totalCols - 1, 0, totalRows - 1, scale, baseRow);
        int[] bboxMin = {
                Math.min(full.x1(), full.x2()), Math.min(full.y1(), full.y2()), Math.min(full.z1(), full.z2())};
        int[] bboxMax = {
                Math.max(full.x1(), full.x2()), Math.max(full.y1(), full.y2()), Math.max(full.z1(), full.z2())};

        return new Result(runs, bboxMin, bboxMax, totalCols * scale, totalRows * scale, inkBlockCount);
    }

    /** Builds the world-space box for pixel columns {@code [colStart,colEnd]} x rows {@code [rowStart,rowEnd]} (both inclusive), scaled. */
    private static Run box(int[] pos, Axes axes, int colStart, int colEnd, int rowStart, int rowEnd, int scale, int baseRow) {
        int colBlockStart = colStart * scale;
        int colBlockEnd = (colEnd + 1) * scale - 1;

        // upOffset decreases as row increases (row 0 = top of text = highest up-offset); the first
        // line's bottom row (baseRow) is offset 0, later lines go negative (below pos).
        int upOffsetHigh = baseRow - rowStart;
        int upOffsetLow = baseRow - rowEnd;
        int upBlockStart = upOffsetLow * scale;
        int upBlockEnd = (upOffsetHigh + 1) * scale - 1;

        int posAdv = pos[axes.advanceAxis()];
        int posUp = pos[axes.upAxis()];

        int advA = posAdv + axes.advanceSign() * colBlockStart;
        int advB = posAdv + axes.advanceSign() * colBlockEnd;
        int upA = posUp + axes.upSign() * upBlockStart;
        int upB = posUp + axes.upSign() * upBlockEnd;

        int[] p1 = point(pos, axes, advA, upA);
        int[] p2 = point(pos, axes, advB, upB);
        return new Run(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2]);
    }

    private static int[] point(int[] pos, Axes axes, int advCoord, int upCoord) {
        int[] out = new int[3];
        out[axes.depthAxis()] = pos[axes.depthAxis()];
        out[axes.advanceAxis()] = advCoord;
        out[axes.upAxis()] = upCoord;
        return out;
    }
}
