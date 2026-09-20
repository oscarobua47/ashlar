// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.text;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Port of {@code mcp-server/src/render/rle.test.ts} (docs/private/prompts/step7b-prompt.md), against
 * {@link BlockGrid#decodeRegionDataJson}. Test names mirror the TS test titles.
 */
class BlockGridTest {

    /** A hand-built 3x2x2 (x by y by z... here dx=3,dy=2,dz=2) region: 12 cells, y outer/z middle/x inner order. */
    private static BlockGrid handBuiltRegion() {
        int[] sequence = {0, 0, 1, 1, 1, 1, 2, 0, 0, 0, 0, 0};
        int[][] runs = toRuns(sequence);
        List<String> palette = List.of("minecraft:air", "minecraft:stone", "minecraft:dirt");
        return BlockGrid.decodeRegionDataJson(new int[]{10, 64, 20}, new int[]{12, 65, 21}, palette, runs, 12);
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

    @Test
    void decodeRegionData_roundTripAgainstAHandBuiltPaletteRunsMatchesTheOriginalSequence() {
        BlockGrid decoded = handBuiltRegion();
        assertEquals(12, decoded.volume());
        assertEquals(new BlockGrid.Size(3, 2, 2), decoded.size());

        List<String> expected = List.of(
                "minecraft:air", "minecraft:air", "minecraft:stone",
                "minecraft:stone", "minecraft:stone", "minecraft:stone",
                "minecraft:dirt", "minecraft:air", "minecraft:air",
                "minecraft:air", "minecraft:air", "minecraft:air"
        );
        assertEquals(expected, decoded.toList());
    }

    @Test
    void decodeRegionData_atPerformsCorrectRandomAccessByAbsoluteWorldCoordinates() {
        BlockGrid decoded = handBuiltRegion();
        assertEquals("minecraft:air", decoded.at(10, 64, 20));
        assertEquals("minecraft:air", decoded.at(11, 64, 20));
        assertEquals("minecraft:stone", decoded.at(12, 64, 20));
        assertEquals("minecraft:dirt", decoded.at(10, 65, 20));
        assertEquals("minecraft:air", decoded.at(11, 65, 20));
        assertEquals("minecraft:air", decoded.at(12, 65, 20));
    }

    @Test
    void decodeRegionData_atThrowsOutsideTheDecodedBounds() {
        BlockGrid decoded = handBuiltRegion();
        assertThrows(IllegalArgumentException.class, () -> decoded.at(999, 64, 20));
        assertThrows(IllegalArgumentException.class, () -> decoded.at(10, 999, 20));
    }

    @Test
    void decodeRegionData_anAllUniformRegionCollapsesToASingleRunAndDecodesCorrectly() {
        List<String> palette = List.of("minecraft:air");
        int[][] runs = {{0, 8}};
        BlockGrid decoded = BlockGrid.decodeRegionDataJson(new int[]{0, 0, 0}, new int[]{1, 1, 1}, palette, runs, 8);
        assertEquals(8, decoded.volume());
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 8; i++) expected.add("minecraft:air");
        assertEquals(expected, decoded.toList());
        assertEquals("minecraft:air", decoded.at(1, 1, 1));
    }

    @Test
    void decodeRegionData_throwsWhenRunsDoNotAddUpToTheRegionVolume() {
        List<String> palette = List.of("minecraft:air");
        int[][] runs = {{0, 5}}; // too short
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BlockGrid.decodeRegionDataJson(new int[]{0, 0, 0}, new int[]{1, 1, 1}, palette, runs, 8));
        assertEquals(true, ex.getMessage().contains("decoded 5 cells but region volume is 8"));
    }
}
