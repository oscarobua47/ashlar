// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.text;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port of {@code mcp-server/src/render/slice.test.ts} (docs/private/prompts/step7b-prompt.md). Test
 * names mirror the TS test titles.
 */
class SliceTextTest {

    @Test
    void sliceChar_mostFrequentBlocksGetTheSpecialCharacterSetFirstInOrder() {
        assertEquals("#", SliceText.sliceChar(0));
        assertEquals("=", SliceText.sliceChar(1));
        assertEquals(":", SliceText.sliceChar(12)); // last of the 13 special chars
        assertEquals("a", SliceText.sliceChar(13)); // falls back to letters (o, x are skipped since already special)
    }

    @Test
    void assignSliceChars_legendOrderMatchesMostFrequentFirstInputOrder() {
        Map<String, String> map = SliceText.assignSliceChars(List.of("minecraft:stone", "minecraft:dirt", "minecraft:oak_log"));
        assertEquals("#", map.get("minecraft:stone"));
        assertEquals("=", map.get("minecraft:dirt"));
        assertEquals("+", map.get("minecraft:oak_log"));
        assertEquals(3, map.size());
    }

    private static int[][] toRuns(int[] sequence) {
        List<int[]> runs = new ArrayList<>();
        for (int idx : sequence) {
            if (!runs.isEmpty() && runs.get(runs.size() - 1)[0] == idx) {
                runs.get(runs.size() - 1)[1]++;
            } else {
                runs.add(new int[]{idx, 1});
            }
        }
        return runs.toArray(new int[0][]);
    }

    /** A single y=10 layer, 5x5 in x/z, whose border is stone and whose interior is air (a hollow square). */
    private static BlockGrid hollowLayerRegion() {
        List<Integer> sequence = new ArrayList<>();
        for (int z = 0; z <= 4; z++) {
            for (int x = 0; x <= 4; x++) {
                boolean isBorder = z == 0 || z == 4 || x == 0 || x == 4;
                sequence.add(isBorder ? 1 : 0);
            }
        }
        int[] seq = sequence.stream().mapToInt(Integer::intValue).toArray();
        List<String> palette = List.of(SliceText.AIR_ID, "minecraft:stone");
        return BlockGrid.decodeRegionDataJson(new int[]{0, 10, 0}, new int[]{4, 10, 4}, palette, toRuns(seq), 25);
    }

    @Test
    void renderSlice_aHollowBoxsMidLayerSliceRendersAsARing() {
        BlockGrid decoded = hollowLayerRegion();
        String text = SliceText.renderSlice(decoded, new SliceText.SliceSpec("y", 10));
        String[] lines = text.split("\n", -1);

        // Layout: [header, "", ruler, row(z=0)..row(z=4), "", ...legend]
        List<String> rows = new ArrayList<>();
        for (int i = 3; i < 8; i++) {
            rows.add(lines[i].substring(7)); // strip the 7-wide gutter
        }
        assertEquals(List.of("#####", "#...#", "#...#", "#...#", "#####"), rows);

        assertTrue(text.contains("Legend:"));
        assertTrue(text.contains(". " + SliceText.AIR_ID));
        assertTrue(text.contains("# minecraft:stone"));
        assertTrue(!text.matches("(?s).*[^\\x00-\\x7F].*"), "output must be pure ASCII");
    }

    @Test
    void renderStats_reportsBlockCountsSortedDescendingWithPercentagesSummingNear100() {
        BlockGrid decoded = hollowLayerRegion();
        String text = SliceText.renderStats(decoded, "world");
        assertTrue(text.contains("2 distinct block state(s)"));
        int stoneIdx = text.indexOf("minecraft:stone");
        int airIdx = text.indexOf(SliceText.AIR_ID);
        assertTrue(stoneIdx >= 0 && airIdx >= 0 && stoneIdx < airIdx);
        assertTrue(text.matches("(?s).*16.*64\\.0%.*minecraft:stone.*"));
    }
}
