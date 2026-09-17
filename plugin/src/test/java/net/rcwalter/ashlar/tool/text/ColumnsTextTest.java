// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link ColumnsText} (step8h-prompt.md: mc_inspect format:"columns"). */
class ColumnsTextTest {

    @Test
    void renderColumns_exactRunsPerColumnBottomToTopFromTheSpecExample() {
        // Mirrors the prompt's own worked example: two columns, one with a stone/dirt/grass/air
        // stack, the other plain stone-then-air, sharing the same z.
        List<String> palette = List.of("minecraft:stone", "minecraft:dirt", "minecraft:grass_block[snowy=false]", "minecraft:air");
        int[][] runs = {
                {0, 10}, // y=60..64, both columns stone
                {0, 1}, {3, 1}, // y=65: x=480 stone, x=481 air
                {1, 1}, {3, 1}, {1, 1}, {3, 1}, {1, 1}, {3, 1}, {1, 1}, {3, 1}, // y=66..69: x=480 dirt, x=481 air
                {2, 1}, {3, 1}, // y=70: x=480 grass, x=481 air
                {3, 10} // y=71..75, both columns air
        };
        BlockGrid decoded = BlockGrid.decodeRegionDataJson(new int[]{480, 60, 130}, new int[]{481, 75, 130}, palette, runs, 32);

        String text = ColumnsText.renderColumns(decoded, "(default)");

        String expected = String.join("\n",
                "Region (default) x=[480..481] y=[60..75] z=[130..130] (2x16x1 = 32 blocks), 2 columns, runs bottom to top (ids without the minecraft: prefix):",
                "480,130: 60-65 stone | 66-69 dirt | 70 grass_block[snowy=false] | 71-75 air",
                "481,130: 60-64 stone | 65-75 air");
        assertEquals(expected, text);
    }

    @Test
    void renderColumns_everyYSingleBlockProducesOneSingleBlockRunPerLine() {
        // A 1x1x1 column at y=5 alone: the run collapses to "y block", not "y-y block".
        List<String> palette = List.of("minecraft:stone");
        int[][] runs = {{0, 1}};
        BlockGrid decoded = BlockGrid.decodeRegionDataJson(new int[]{5, 5, 5}, new int[]{5, 5, 5}, palette, runs, 1);

        String text = ColumnsText.renderColumns(decoded, "world");

        assertEquals(String.join("\n",
                "Region world x=[5..5] y=[5..5] z=[5..5] (1x1x1 = 1 blocks), 1 columns, runs bottom to top (ids without the minecraft: prefix):",
                "5,5: 5 stone"), text);
    }

    @Test
    void renderColumns_aColumnThatIsAllOneBlockCollapsesToASingleRun() {
        List<String> palette = List.of("minecraft:stone");
        int[][] runs = {{0, 6}}; // y=10..15, all stone
        BlockGrid decoded = BlockGrid.decodeRegionDataJson(new int[]{0, 10, 0}, new int[]{0, 15, 0}, palette, runs, 6);

        String text = ColumnsText.renderColumns(decoded, "world");
        String[] lines = text.split("\n", -1);

        assertEquals("0,0: 10-15 stone", lines[1]);
    }

    @Test
    void renderColumns_ordersRowsZOuterAndColumnsXInner() {
        // 2x1x2 region (x=[0,1], y=5, z=[0,1]) with a distinct block per cell.
        List<String> palette = List.of("minecraft:a", "minecraft:b", "minecraft:c", "minecraft:d");
        int[][] runs = {{0, 1}, {1, 1}, {2, 1}, {3, 1}};
        BlockGrid decoded = BlockGrid.decodeRegionDataJson(new int[]{0, 5, 0}, new int[]{1, 5, 1}, palette, runs, 4);

        String text = ColumnsText.renderColumns(decoded, "world");
        String[] lines = text.split("\n", -1);

        assertEquals(5, lines.length); // header + 4 columns
        assertEquals("0,0: 5 a", lines[1]);
        assertEquals("1,0: 5 b", lines[2]);
        assertEquals("0,1: 5 c", lines[3]);
        assertEquals("1,1: 5 d", lines[4]);
    }

    @Test
    void renderColumns_minecraftPrefixIsStrippedButOtherNamespacesStayIntact() {
        List<String> palette = List.of("minecraft:stone", "modid:custom_block[foo=bar]");
        int[][] runs = {{0, 1}, {1, 2}}; // y=10 stone, y=11..12 modid:custom_block
        BlockGrid decoded = BlockGrid.decodeRegionDataJson(new int[]{5, 10, 5}, new int[]{5, 12, 5}, palette, runs, 3);

        String text = ColumnsText.renderColumns(decoded, "world");
        String[] lines = text.split("\n", -1);

        assertEquals("5,5: 10 stone | 11-12 modid:custom_block[foo=bar]", lines[1]);
    }
}
