// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.text;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of {@code mcp-server/src/render/relief.test.ts} (docs/private/prompts/step7b-prompt.md).
 * Test names mirror the TS test titles so the mapping is obvious.
 */
class ReliefTextTest {

    private static final int GROUND = ReliefText.CLASS_GROUND;
    private static final int LIQUID = ReliefText.CLASS_LIQUID;
    private static final int VEGETATION = ReliefText.CLASS_VEGETATION;

    @Test
    void reliefChar_liquidAlwaysRendersAsTilde() {
        assertEquals("~", ReliefText.reliefChar(70, 60, 80, LIQUID));
        assertEquals("~", ReliefText.reliefChar(60, 60, 80, LIQUID));
        assertEquals("~", ReliefText.reliefChar(80, 60, 80, LIQUID));
    }

    @Test
    void reliefChar_vegetationAlwaysRendersAsT_regardlessOfHeight() {
        assertEquals("T", ReliefText.reliefChar(70, 60, 80, VEGETATION));
        assertEquals("T", ReliefText.reliefChar(60, 60, 80, VEGETATION));
        assertEquals("T", ReliefText.reliefChar(80, 60, 80, VEGETATION));
    }

    @Test
    void reliefChar_flatAreaMinEqualsMaxRendersAsEquals() {
        assertEquals("=", ReliefText.reliefChar(64, 64, 64, GROUND));
    }

    @Test
    void reliefChar_minimumBucketIsDotMaximumIsAt() {
        assertEquals(".", ReliefText.reliefChar(60, 60, 68, GROUND));
        assertEquals("@", ReliefText.reliefChar(68, 60, 68, GROUND));
    }

    @Test
    void reliefChar_bucketsIncreaseMonotonicallyWithHeight() {
        List<String> order = List.of(".", ",", ":", "-", "=", "+", "*", "#");
        int min = 0, max = 80;
        int lastRank = -1;
        for (int h = min; h < max; h++) {
            String c = ReliefText.reliefChar(h, min, max, GROUND);
            int rank = order.indexOf(c);
            assertTrue(rank >= 0, "unexpected char '" + c + "'");
            assertTrue(rank >= lastRank, "bucket rank went backwards at h=" + h);
            lastRank = rank;
        }
    }

    @Test
    void computeStep_noDownsamplingUnderTheThresholds() {
        assertEquals(1, ReliefText.computeStep(50, 50));
        assertEquals(1, ReliefText.computeStep(80, 60));
    }

    @Test
    void computeStep_downsamplesWideDeepAreas() {
        assertEquals(2, ReliefText.computeStep(160, 60));
        assertEquals(2, ReliefText.computeStep(80, 120));
        assertEquals(4, ReliefText.computeStep(240, 240));
    }

    @Test
    void downsample_takesTheMedianHeightExcludingVegetationAndAnyLiquidVegetationPerStepBlock() {
        int[][] heights = {
                {10, 10, 20, 20},
                {10, 12, 20, 22},
                {30, 30, 40, 40},
                {30, 32, 40, 42}
        };
        int[][] classes = {
                {GROUND, GROUND, GROUND, GROUND},
                {GROUND, GROUND, GROUND, LIQUID},
                {GROUND, GROUND, GROUND, GROUND},
                {GROUND, GROUND, GROUND, GROUND}
        };
        ReliefText.DownsampleResult result = ReliefText.downsample(heights, classes, 2);
        assertArrayEquals2D(new int[][]{{10, 20}, {30, 40}}, result.heights());
        assertArrayEquals2D(new int[][]{{GROUND, LIQUID}, {GROUND, GROUND}}, result.classes());
    }

    @Test
    void downsample_vegetationCellHeightExcludedFromBlockMedian() {
        int[][] heights = {
                {60, 60},
                {60, 90}
        };
        int[][] classes = {
                {GROUND, GROUND},
                {GROUND, VEGETATION}
        };
        ReliefText.DownsampleResult result = ReliefText.downsample(heights, classes, 2);
        assertArrayEquals2D(new int[][]{{60}}, result.heights());
        assertArrayEquals2D(new int[][]{{VEGETATION}}, result.classes());
    }

    @Test
    void downsample_allVegetationBlockFallsBackToRawMedian() {
        int[][] heights = {
                {10, 20},
                {30, 40}
        };
        int[][] classes = {
                {VEGETATION, VEGETATION},
                {VEGETATION, VEGETATION}
        };
        ReliefText.DownsampleResult result = ReliefText.downsample(heights, classes, 2);
        assertEquals(30, result.heights()[0][0]); // median of the raw [10,20,30,40] set (index floor(4/2)=2)
        assertArrayEquals2D(new int[][]{{VEGETATION}}, result.classes());
    }

    @Test
    void downsample_stepOneIsANoOpCopy() {
        int[][] heights = {{1, 2}, {3, 4}};
        int[][] classes = {{GROUND, LIQUID}, {GROUND, GROUND}};
        ReliefText.DownsampleResult result = ReliefText.downsample(heights, classes, 1);
        assertArrayEquals2D(heights, result.heights());
        assertArrayEquals2D(classes, result.classes());
        assertNotEquals(heights, result.heights(), "must be a copy, not the same array reference");
    }

    @Test
    void buildLegend_flatRegionCollapsesToASingleEqualsEntry() {
        List<ReliefText.LegendEntry> legend = ReliefText.buildLegend(64, 64, false, false);
        assertEquals(List.of(new ReliefText.LegendEntry("=", "64")), legend);
    }

    @Test
    void buildLegend_8BucketsPlusAtForTheMaxTildeOnlyWhenLiquidTOnlyWhenVegetation() {
        List<ReliefText.LegendEntry> bare = ReliefText.buildLegend(60, 68, false, false);
        assertEquals(9, bare.size());
        assertEquals(".", bare.get(0).symbol());
        assertEquals("#", bare.get(7).symbol());
        assertEquals("@", bare.get(8).symbol());
        assertEquals("68", bare.get(8).label());
        assertTrue(bare.stream().noneMatch(e -> e.symbol().equals("~")));
        assertTrue(bare.stream().noneMatch(e -> e.symbol().equals("T")));

        List<ReliefText.LegendEntry> withLiquid = ReliefText.buildLegend(60, 68, true, false);
        assertEquals(10, withLiquid.size());
        assertEquals("~", withLiquid.get(9).symbol());
        assertEquals("liquid surface", withLiquid.get(9).label());

        List<ReliefText.LegendEntry> withBoth = ReliefText.buildLegend(60, 68, true, true);
        assertEquals(11, withBoth.size());
        assertEquals("~", withBoth.get(9).symbol());
        assertEquals("T", withBoth.get(10).symbol());
        assertEquals("trees / vegetation", withBoth.get(10).label());
    }

    @Test
    void largestFlatZone_findsAnEmbeddedFlatRectangleInASynthetic50x50Map() {
        int size = 50;
        int[][] heights = new int[size][size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                heights[z][x] = x % 2 == 0 ? 40 : 90;
            }
        }
        int rowStart = 5, rowEnd = 18, colStart = 10, colEnd = 27;
        for (int z = rowStart; z <= rowEnd; z++) {
            for (int x = colStart; x <= colEnd; x++) {
                heights[z][x] = 66;
            }
        }

        ReliefText.FlatZone zone = ReliefText.largestFlatZone(heights, 66, 1);
        assertNotNull(zone, "expected a flat zone to be found");
        assertEquals(rowStart, zone.row1());
        assertEquals(rowEnd, zone.row2());
        assertEquals(colStart, zone.col1());
        assertEquals(colEnd, zone.col2());
        assertEquals(rowEnd - rowStart + 1, zone.rows());
        assertEquals(colEnd - colStart + 1, zone.cols());
    }

    @Test
    void largestFlatZone_aVegetationCellInsideAnOtherwiseFlatRectangleIsNeverIncluded() {
        int size = 10;
        int[][] heights = new int[size][size];
        for (int[] row : heights) java.util.Arrays.fill(row, 64);
        int[][] classes = new int[size][size];
        classes[5][5] = VEGETATION;

        ReliefText.FlatZone zone = ReliefText.largestFlatZone(heights, 64, 1, classes);
        assertNotNull(zone, "expected a flat zone to be found");
        boolean areaCoversVegetationCell = zone.row1() <= 5 && 5 <= zone.row2() && zone.col1() <= 5 && 5 <= zone.col2();
        assertFalse(areaCoversVegetationCell, "the vegetation cell must not be inside the reported flat zone");
    }

    @Test
    void largestFlatZone_returnsNullForAnEmptyGrid() {
        assertNull(ReliefText.largestFlatZone(new int[0][], 0, 1));
    }

    @Test
    void renderRelief_producesPureAsciiOutputContainingTheSummaryLegendAndFlatZoneLine() {
        int size = 20;
        int[][] heights = new int[size][size];
        int[][] classes = new int[size][size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                heights[z][x] = 64 + ((x + z) % 5);
                classes[z][x] = GROUND;
            }
        }
        Map<String, Long> surface = new LinkedHashMap<>();
        surface.put("minecraft:grass_block", 300L);
        surface.put("minecraft:stone", 100L);
        String text = ReliefText.renderRelief(new ReliefText.ReliefInput(new int[]{100, 200}, new int[]{119, 219}, heights, classes, surface, null));
        assertTrue(text.startsWith("Area x=[100..119] z=[200..219]"));
        assertTrue(text.contains("Largest flat zone"));
        assertTrue(text.contains("Legend:"));
        assertFalse(text.matches("(?s).*[^\\x00-\\x7F].*"), "output must be pure ASCII");
    }

    @Test
    void renderRelief_aForestNoLongerPaintsAsWaterAndInsteadShowsTWithCorrectMinMaxExcludingTrunkTops() {
        int size = 10;
        int[][] heights = new int[size][size];
        for (int[] row : heights) java.util.Arrays.fill(row, 64);
        int[][] classes = new int[size][size];
        heights[4][4] = 68;
        heights[4][5] = 68;
        classes[4][4] = VEGETATION;
        classes[4][5] = VEGETATION;

        Map<String, Long> surface = new LinkedHashMap<>();
        surface.put("minecraft:grass_block", 96L);
        surface.put("minecraft:oak_log", 4L);
        String text = ReliefText.renderRelief(new ReliefText.ReliefInput(new int[]{0, 0}, new int[]{size - 1, size - 1}, heights, classes, surface, null));

        assertFalse(text.contains("~"), "no liquid cells exist, so no ~ should appear anywhere");
        assertTrue(text.contains("T"), "the tree canopy must render as T");
        assertTrue(text.contains("min 64, max 64"), "min/max must exclude the trunk-top height of 68");
    }

    private static void assertArrayEquals2D(int[][] expected, int[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(java.util.Arrays.toString(expected[i]), java.util.Arrays.toString(actual[i]));
        }
    }
}
