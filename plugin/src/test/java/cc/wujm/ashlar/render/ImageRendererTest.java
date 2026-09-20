// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ImageRenderer} (docs/prompts/step4e-prompt.md
 * &sect;Verify point 1). Pure Java, no Bukkit, no server: {@link
 * cc.wujm.ashlar.engine.MapColorResolver}'s Bukkit-dependent map
 * color lookup is stood in for here by hand-picked ARGB ints, exactly as
 * {@code RenderHandler} would pass in after {@link
 * cc.wujm.ashlar.engine.RenderTask} resolves them on the main
 * thread.
 */
class ImageRendererTest {

    // A synthetic 4(x) x 3(y) x 4(z) region, x/z: 0..3, y: 0..2.
    //   y=0 (floor): stone everywhere.
    //   y=1: a stone ridge along x=1, for every z; air elsewhere.
    //   y=2: a single dirt block at (x=1,z=2), on top of the ridge; air elsewhere.
    // Column heights: x=0 and x=2/3 top out at y=0 (stone); x=1 tops out at
    // y=1 (stone) except z=2, which tops out at y=2 (dirt) - a one-block-taller
    // "step" whose north (z=1) and south (z=3) neighbours are both shorter,
    // exercising all three top-view shade levels.
    private static final Region REGION_4x3x4 = new Region(0, 0, 0, 3, 2, 3);

    private static RegionData buildRegion() {
        RegionData.Encoder encoder = new RegionData.Encoder();
        // y=0: stone everywhere (z outer, x inner - matches RegionData's y,z,x order).
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                encoder.add("stone");
            }
        }
        // y=1: stone ridge at x=1.
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                encoder.add(x == 1 ? "stone" : "air");
            }
        }
        // y=2: single dirt block at (x=1,z=2).
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                encoder.add((x == 1 && z == 2) ? "dirt" : "air");
            }
        }
        return encoder.finish(REGION_4x3x4);
    }

    // Palette assigned in first-seen order by the Encoder: "stone" (y=0,z=0,x=0) -> 0,
    // "air" (y=1,z=0,x=0) -> 1, "dirt" (y=2,z=2,x=1) -> 2.
    private static final int STONE_ARGB = 0xFF808080;
    private static final int AIR_ARGB = 0; // transparent
    private static final int DIRT_ARGB = 0xFF654321;
    private static final int[] PALETTE_ARGB = {STONE_ARGB, AIR_ARGB, DIRT_ARGB};

    @Test
    void topViewPicksHighestBlockAndShadesLikeAVanillaMap() {
        RegionData data = buildRegion();
        ImageRenderer.Output out = ImageRenderer.render(data, PALETTE_ARGB, "top", null, 0, 1, 0);

        assertEquals(4, out.width());
        assertEquals(4, out.height());
        assertEquals(1, out.scale());
        assertEquals("+x (east)", out.axisRight());
        assertEquals("+z (south)", out.axisDown());
        assertEquals(0, out.topLeftA());
        assertEquals(0, out.topLeftB());

        // row 0 (z=0): forced "equal" shade (86%) regardless of neighbours, for every column.
        assertEquals(shaded(STONE_ARGB, 86), pixelAt(out, 0, 0), "x=0,z=0 highest is the y=0 floor");
        assertEquals(shaded(STONE_ARGB, 86), pixelAt(out, 1, 0), "x=1,z=0 highest is the y=1 ridge, top row forced 86%");
        assertEquals(shaded(STONE_ARGB, 86), pixelAt(out, 2, 0));

        // row 2 (z=2): x=1 steps UP from its north neighbour (z=1, height 1) to height 2 -> 100%.
        assertEquals(shaded(DIRT_ARGB, 100), pixelAt(out, 1, 2), "x=1,z=2 is taller than its north neighbour");

        // row 3 (z=3): x=1 steps DOWN from its north neighbour (z=2, height 2) to height 1 -> 71%.
        assertEquals(shaded(STONE_ARGB, 71), pixelAt(out, 1, 3), "x=1,z=3 is shorter than its north neighbour");

        // row 1 (z=1): x=1 is the same height as its north neighbour (z=0) -> 86% (equal, not the forced top-row case).
        assertEquals(shaded(STONE_ARGB, 86), pixelAt(out, 1, 1));

        // x=0 and x=2/3 never change height between rows, so they are "equal" (86%) throughout.
        for (int row = 0; row < 4; row++) {
            assertEquals(shaded(STONE_ARGB, 86), pixelAt(out, 0, row), "x=0 row=" + row);
            assertEquals(shaded(STONE_ARGB, 86), pixelAt(out, 2, row), "x=2 row=" + row);
            assertEquals(shaded(STONE_ARGB, 86), pixelAt(out, 3, row), "x=3 row=" + row);
        }

        assertEquals(2, out.legend().size(), "two distinct non-air blocks appear: stone and dirt");
    }

    @Test
    void sliceIsExactColorAndTransparentForAir() {
        RegionData data = buildRegion();
        // y=1 layer: stone ridge at x=1, air elsewhere - no shading, exact colors.
        ImageRenderer.Output out = ImageRenderer.render(data, PALETTE_ARGB, "slice", "y", 1, 1, 0);

        assertEquals(4, out.width());
        assertEquals(4, out.height());
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                int expected = col == 1 ? STONE_ARGB : checker(col, row);
                assertEquals(expected, pixelAt(out, col, row), "col=" + col + " row=" + row);
            }
        }
        // Exactly one non-transparent block state at this layer.
        assertEquals(1, out.legend().size());
        assertEquals("stone", out.legend().get(0).block());
    }

    @Test
    void gridLinePositionsAndThickness() {
        // A 10x1x1 strip, all stone, so the only variable is the grid overlay itself.
        Region region = new Region(0, 0, 0, 9, 0, 0);
        RegionData.Encoder encoder = new RegionData.Encoder();
        for (int x = 0; x < 10; x++) {
            encoder.add("stone");
        }
        RegionData data = encoder.finish(region);

        int scale = 4;
        int grid = 3; // minor lines at x=0,3,6,9; major (5*grid=15) only at x=0.
        ImageRenderer.Output out = ImageRenderer.render(data, new int[]{STONE_ARGB}, "top", null, 0, scale, grid);

        assertEquals(40, out.width());
        assertEquals(4, out.height());

        // blocksTall is 1, so every column is the forced-top-row 86% shade; the base
        // (ungridded) color is the same everywhere.
        int base = shaded(STONE_ARGB, 86);
        int blended = blend40PercentBlack(base);

        // y=3 is below the row-0 horizontal grid line's 2px thickness (rows 0-1), so it
        // isolates the vertical lines. (rawPixelAt takes physical pixel coordinates,
        // unlike pixelAt's block coordinates.)
        assertEquals(blended, rawPixelAt(out, 0, 3), "x=0: major vertical line (grid-aligned and 5*grid-aligned)");
        assertEquals(blended, rawPixelAt(out, 1, 3), "x=1: still inside the 2px major line");
        assertEquals(base, rawPixelAt(out, 2, 3), "x=2: outside the major line's 2px width");
        assertEquals(base, rawPixelAt(out, 5, 3), "x=5 (block col=1, world x=1): not grid-aligned");
        assertEquals(blended, rawPixelAt(out, 12, 3), "x=12 (block col=3, world x=3): minor vertical line");
        assertEquals(base, rawPixelAt(out, 13, 3), "x=13: outside the minor line's 1px width");

        // Row 0 carries the (major, since world z=0 is also 5*grid-aligned) horizontal line;
        // check a column with no vertical line there to isolate it.
        assertEquals(blended, rawPixelAt(out, 20, 0), "y=0: horizontal grid line (block col=5, world x=5, not vertically aligned)");
        assertEquals(blended, rawPixelAt(out, 20, 1), "y=1: still inside the 2px horizontal line");
        assertEquals(base, rawPixelAt(out, 20, 2), "y=2: outside the horizontal line's 2px height");
    }

    @Test
    void bitmapFontRendersDigitsWithWhiteFillAndBlackOutline() {
        int[] pixels = ImageRenderer.renderLabelForTest("10", 1, 20, 10);

        // '1' glyph (0,0): {"010","110","010","010","111"} - the lone top pixel is on at (1,0).
        assertEquals(0xFFFFFFFF, pixels[idx(20, 1, 0)], "'1' top pixel is white");
        assertEquals(0xFF000000, pixels[idx(20, 0, 0)], "left of '1' top pixel is a black outline pixel");

        // '0' glyph starts at cursor = 0 + 3*1 (glyph width) + 1 (spacing) = 4.
        assertEquals(0xFFFFFFFF, pixels[idx(20, 4, 0)], "'0' top-left corner is on");
        assertEquals(0xFFFFFFFF, pixels[idx(20, 6, 0)], "'0' top-right corner is on");
        assertEquals(0xFF000000, pixels[idx(20, 5, 1)], "'0' middle-top gap is outlined, not filled");

        // Far away from both glyphs: untouched, still fully transparent.
        assertEquals(0, pixels[idx(20, 15, 8)]);
    }

    private static int idx(int width, int x, int y) {
        return y * width + x;
    }

    private static int pixelAt(ImageRenderer.Output out, int blockCol, int blockRow) {
        int px = blockCol * out.scale();
        int py = blockRow * out.scale();
        return out.pixels()[py * out.width() + px];
    }

    private static int rawPixelAt(ImageRenderer.Output out, int x, int y) {
        return out.pixels()[y * out.width() + x];
    }

    private static int checker(int col, int row) {
        return ((col + row) & 1) == 0 ? 0xFF3A3A3A : 0xFF4A4A4A;
    }

    private static int shaded(int argb, int pct) {
        int a = (argb >>> 24) & 0xFF;
        int rr = Math.min(255, ((argb >> 16) & 0xFF) * pct / 100);
        int gg = Math.min(255, ((argb >> 8) & 0xFF) * pct / 100);
        int bb = Math.min(255, (argb & 0xFF) * pct / 100);
        return (a << 24) | (rr << 16) | (gg << 8) | bb;
    }

    private static int blend40PercentBlack(int base) {
        int a = (base >>> 24) & 0xFF;
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int rr = (int) Math.round(br * 0.6);
        int gg = (int) Math.round(bg * 0.6);
        int bbl = (int) Math.round(bb * 0.6);
        return (a << 24) | (rr << 16) | (gg << 8) | bbl;
    }
}
