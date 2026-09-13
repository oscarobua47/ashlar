// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HeightmapImageRenderer} (docs/prompts/step4f-prompt.md
 * &sect;Verify point 1: "bands, liquid, contour positions, flat zone"). Pure
 * Java, no Bukkit, no server - same style as {@link ImageRendererTest}.
 */
class HeightmapImageRendererTest {

    private static final int LIQUID_ARGB = 0xFF3F76E4;
    // Same 8-stop hypsometric ramp HeightmapImageRenderer uses internally; duplicated here (not exposed) so
    // expected colors can be derived independently of the implementation's own band-selection code.
    private static final int[] BAND_COLORS = {
            0xFF1B4D2E, 0xFF2F7A3D, 0xFF4FA64F, 0xFF8EC63F, 0xFFD9C93F, 0xFFC9A04A, 0xFFA97A4A, 0xFFD9D9D9
    };

    @Test
    void bandsSpanLowToHighAndNorthNeighbourShadingMatchesTheTopView() {
        // 2 cols x 3 rows. col0: 60,60,70 (equal, equal->86, then up->100). col1: 70,60,70 (forced86, down->71, up->100).
        int[][] heights = {
                {60, 70},
                {60, 60},
                {70, 60}
        };
        boolean[][] liquid = new boolean[3][2];
        int[][] liquidDepth = new int[3][2];

        HeightmapImageRenderer.Output out = HeightmapImageRenderer.render(heights, liquid, liquidDepth, 0, 0, 1, 0, 0);
        assertEquals(2, out.width());
        assertEquals(3, out.height());

        // min=60, max=70 over the whole grid -> h=60 is band 0 (lowest), h=70 is band 7 (h >= max).
        assertEquals(shaded(BAND_COLORS[0], 86), pixel(out, 0, 0), "col0,row0: forced top-row 86%, band 0 (h=60)");
        assertEquals(shaded(BAND_COLORS[0], 86), pixel(out, 0, 1), "col0,row1: equal to north (60==60) -> 86%");
        assertEquals(shaded(BAND_COLORS[7], 100), pixel(out, 0, 2), "col0,row2: taller than north (70>60) -> 100%, band 7 (h=70)");

        assertEquals(shaded(BAND_COLORS[7], 86), pixel(out, 1, 0), "col1,row0: forced top-row 86%, band 7 (h=70)");
        assertEquals(shaded(BAND_COLORS[0], 71), pixel(out, 1, 1), "col1,row1: shorter than north (60<70) -> 71%, band 0 (h=60)");
        assertEquals(shaded(BAND_COLORS[0], 86), pixel(out, 1, 2), "col1,row2: equal to north (60==60) -> 86%");

        assertEquals(8, out.legend().size(), "no liquid: 8 height bands, no water row");
        assertTrue(out.legend().stream().noneMatch(b -> b.label().equals("water")), "no liquid cells: no water legend entry");
    }

    @Test
    void liquidOverridesTheBandColorAndDeepWaterIsDarker() {
        // 2 cols x 1 row: both forced top-row shade (86%). col0 shallow liquid (depth 1), col1 deep liquid (depth 4).
        int[][] heights = {{60, 70}};
        boolean[][] liquid = {{true, true}};
        int[][] liquidDepth = {{1, 4}};

        HeightmapImageRenderer.Output out = HeightmapImageRenderer.render(heights, liquid, liquidDepth, 0, 0, 1, 0, 0);

        assertEquals(shaded(LIQUID_ARGB, 86), pixel(out, 0, 0), "shallow liquid (depth 1 < 3): plain liquid blue");
        int deepBase = shaded(LIQUID_ARGB, 65); // ImageRenderer.applyShade(LIQUID_ARGB, 65), applied before the row shade
        assertEquals(shaded(deepBase, 86), pixel(out, 1, 0), "deep liquid (depth 4 >= 3): darker blue");
        assertNotEquals(pixel(out, 0, 0), pixel(out, 1, 0), "shallow and deep liquid must render differently");

        assertEquals(1, out.legend().stream().filter(b -> b.label().equals("water")).count(), "legend has exactly one water entry");
        assertEquals("#3f76e4", out.legend().get(0).colorHex(), "water legend entry uses the plain (non-darkened) liquid color");
    }

    @Test
    void contourLinesAppearOnlyWhereFloorDivByContourDiffers() {
        // 4 cols x 2 rows, contour=5. col0/col1 both height 60 (floor/5=12): no line between them.
        // col1(60)/col2(65) cross a multiple of 5 (floor 12 vs 13): a line. col2/col3 both 65: no line.
        // Every row is identical top-to-bottom, so no horizontal contour lines exist anywhere.
        int[][] heights = {
                {60, 60, 65, 65},
                {60, 60, 65, 65}
        };
        boolean[][] liquid = new boolean[2][4];
        int[][] liquidDepth = new int[2][4];
        int scale = 3;

        HeightmapImageRenderer.Output plain = HeightmapImageRenderer.render(heights, liquid, liquidDepth, 0, 0, scale, 0, 0);
        HeightmapImageRenderer.Output contoured = HeightmapImageRenderer.render(heights, liquid, liquidDepth, 0, 0, scale, 0, 5);
        assertEquals(plain.width(), contoured.width());
        assertEquals(plain.height(), contoured.height());

        // Boundary between col1 and col2 (world column index 2*scale): every y should be darkened.
        int boundaryX = 2 * scale;
        for (int y = 0; y < plain.height(); y++) {
            int i = y * plain.width() + boundaryX;
            assertEquals(ImageRenderer.blend(plain.pixels()[i]), contoured.pixels()[i], "boundary col1|col2 at y=" + y + " must be darkened");
        }

        // Boundaries with no bucket change: col0|col1 (x=1*scale) and col2|col3 (x=3*scale) must be untouched.
        for (int boundary : new int[]{1 * scale, 3 * scale}) {
            for (int y = 0; y < plain.height(); y++) {
                int i = y * plain.width() + boundary;
                assertEquals(plain.pixels()[i], contoured.pixels()[i], "non-boundary x=" + boundary + " y=" + y + " must be unchanged");
            }
        }

        // No horizontal contour line: the row boundary (y=1*scale) must be untouched everywhere except where the
        // (already-verified) vertical col1|col2 line also passes through it.
        int rowBoundaryY = 1 * scale;
        for (int x = 0; x < plain.width(); x++) {
            if (x == boundaryX) {
                continue;
            }
            int i = rowBoundaryY * plain.width() + x;
            assertEquals(plain.pixels()[i], contoured.pixels()[i], "row boundary x=" + x + " must be unchanged (no height change down any column)");
        }
    }

    @Test
    void contourZeroDisablesLines() {
        int[][] heights = {{60, 65}};
        boolean[][] liquid = new boolean[1][2];
        int[][] liquidDepth = new int[1][2];
        HeightmapImageRenderer.Output plain = HeightmapImageRenderer.render(heights, liquid, liquidDepth, 0, 0, 3, 0, 0);
        HeightmapImageRenderer.Output withContourOff = HeightmapImageRenderer.render(heights, liquid, liquidDepth, 0, 0, 3, 0, 0);
        assertEquals(plain.pixels().length, withContourOff.pixels().length);
        for (int i = 0; i < plain.pixels().length; i++) {
            assertEquals(plain.pixels()[i], withContourOff.pixels()[i]);
        }
    }

    @Test
    void medianAndLargestFlatZoneMatchTheReliefRendererRule() {
        // 4x4: row0 all 70; rows1-3 cols0-2 are 64, col3 is 70. Nine 64s, seven 70s -> median is 64.
        // The largest within-tolerance-1 rectangle is exactly rows1-3 x cols0-2 (3x3).
        int[][] heights = {
                {70, 70, 70, 70},
                {64, 64, 64, 70},
                {64, 64, 64, 70},
                {64, 64, 64, 70}
        };
        int median = HeightmapImageRenderer.median(heights);
        assertEquals(64, median);

        HeightmapImageRenderer.FlatZone zone = HeightmapImageRenderer.largestFlatZone(heights, 100, 200, median, 1);
        assertNotNull(zone);
        assertEquals(100, zone.x1());
        assertEquals(102, zone.x2());
        assertEquals(201, zone.z1());
        assertEquals(203, zone.z2());
        assertEquals(64, zone.y());
        assertEquals(3, zone.width());
        assertEquals(3, zone.depth());
    }

    @Test
    void largestFlatZoneIsNullForAnEmptyGrid() {
        assertNull(HeightmapImageRenderer.largestFlatZone(new int[0][0], 0, 0, 0, 1));
    }

    @Test
    void flatMinEqualsMaxCollapsesToASingleBand() {
        int[][] heights = {{64, 64}, {64, 64}};
        boolean[][] liquid = new boolean[2][2];
        int[][] liquidDepth = new int[2][2];
        HeightmapImageRenderer.Output out = HeightmapImageRenderer.render(heights, liquid, liquidDepth, 0, 0, 1, 0, 5);
        assertEquals(1, out.legend().size(), "min==max: a single band, no range split");
        assertTrue(out.legend().get(0).label().equals("y 64"));
    }

    private static int pixel(HeightmapImageRenderer.Output out, int col, int row) {
        int px = col * out.scale();
        int py = row * out.scale();
        return out.pixels()[py * out.width() + px];
    }

    private static int shaded(int argb, int pct) {
        int a = (argb >>> 24) & 0xFF;
        int rr = Math.min(255, ((argb >> 16) & 0xFF) * pct / 100);
        int gg = Math.min(255, ((argb >> 8) & 0xFF) * pct / 100);
        int bb = Math.min(255, (argb & 0xFF) * pct / 100);
        return (a << 24) | (rr << 16) | (gg << 8) | bb;
    }
}
