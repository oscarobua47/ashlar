// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TextExpand} (step8k-prompt.md &sect;C): the facing table for a single "I"
 * glyph (whose glyph pattern is a full-width top/bottom serif and a single-column stem - see
 * {@link Font5x7Test}), scale doubling, the flat {@code up} facing, and that the background box
 * covers the whole cell (not just the ink).
 *
 * <p>{@code pos = [100, 64, 200]} throughout: per step8k-prompt.md &sect;B, {@code pos} is the block
 * of the bottom-left pixel of the first glyph, so for every facing the bottom row of "I" (row 6,
 * a full 5-wide serif) must land exactly on {@code pos} at its "start" corner.
 */
class TextExpandTest {

    private static final int[] POS = {100, 64, 200};

    @Test
    void southAdvancesPlusXUpPlusYPlaneAtFixedZ() {
        TextExpand.Result r = TextExpand.expand("I", POS, "south", 1, 1);
        // bounding box: x [100,104] (5 wide), y [64,70] (7 tall), z fixed at 200
        assertArrayEquals(new int[] {100, 64, 200}, r.bboxMin());
        assertArrayEquals(new int[] {104, 70, 200}, r.bboxMax());
        assertEquals(5, r.widthBlocks());
        assertEquals(7, r.heightBlocks());
        // bottom serif run (row 6): full 5-wide row at pos's own y
        assertRunPresent(r.inkRuns(), 100, 64, 200, 104, 64, 200);
        // top serif run (row 0): full 5-wide row, 6 above pos
        assertRunPresent(r.inkRuns(), 100, 70, 200, 104, 70, 200);
        // stem (rows 1-5, single column at x=102)
        assertRunPresent(r.inkRuns(), 102, 69, 200, 102, 69, 200);
        assertRunPresent(r.inkRuns(), 102, 65, 200, 102, 65, 200);
        assertEquals(15, r.inkBlockCount());
    }

    @Test
    void northAdvancesMinusXUpPlusYPlaneAtFixedZ() {
        TextExpand.Result r = TextExpand.expand("I", POS, "north", 1, 1);
        assertArrayEquals(new int[] {96, 64, 200}, r.bboxMin());
        assertArrayEquals(new int[] {100, 70, 200}, r.bboxMax());
        assertRunPresent(r.inkRuns(), 100, 64, 200, 96, 64, 200);
        assertRunPresent(r.inkRuns(), 100, 70, 200, 96, 70, 200);
    }

    @Test
    void eastAdvancesMinusZUpPlusYPlaneAtFixedX() {
        TextExpand.Result r = TextExpand.expand("I", POS, "east", 1, 1);
        assertArrayEquals(new int[] {100, 64, 196}, r.bboxMin());
        assertArrayEquals(new int[] {100, 70, 200}, r.bboxMax());
        assertRunPresent(r.inkRuns(), 100, 64, 200, 100, 64, 196);
        assertRunPresent(r.inkRuns(), 100, 70, 200, 100, 70, 196);
    }

    @Test
    void westAdvancesPlusZUpPlusYPlaneAtFixedX() {
        TextExpand.Result r = TextExpand.expand("I", POS, "west", 1, 1);
        assertArrayEquals(new int[] {100, 64, 200}, r.bboxMin());
        assertArrayEquals(new int[] {100, 70, 204}, r.bboxMax());
        assertRunPresent(r.inkRuns(), 100, 64, 200, 100, 64, 204);
    }

    @Test
    void upLiesFlatAdvancePlusXTextUpIsMinusZPlaneAtFixedY() {
        TextExpand.Result r = TextExpand.expand("I", POS, "up", 1, 1);
        // pos is the bottom row (row 6) -> z = pos.z (200, the "south"/near edge)
        // row 0 (top of glyph, "up" of the text) is 6 further north -> z = 194
        assertArrayEquals(new int[] {100, 64, 194}, r.bboxMin());
        assertArrayEquals(new int[] {104, 64, 200}, r.bboxMax());
        assertRunPresent(r.inkRuns(), 100, 64, 200, 104, 64, 200); // bottom serif, at pos.z
        assertRunPresent(r.inkRuns(), 100, 64, 194, 104, 64, 194); // top serif, 6 blocks north
        // every ink run lies in the single y = pos.y plane
        for (TextExpand.Run run : r.inkRuns()) {
            assertEquals(64, run.y1());
            assertEquals(64, run.y2());
        }
    }

    @Test
    void scaleTwoDoublesEveryRunAndTheBoundingBox() {
        TextExpand.Result r = TextExpand.expand("I", POS, "south", 2, 1);
        assertArrayEquals(new int[] {100, 64, 200}, r.bboxMin());
        assertArrayEquals(new int[] {109, 77, 200}, r.bboxMax()); // 10 wide, 14 tall
        assertEquals(10, r.widthBlocks());
        assertEquals(14, r.heightBlocks());
        // bottom serif at scale 2: x[100,109], y[64,65]
        assertRunPresent(r.inkRuns(), 100, 64, 200, 109, 65, 200);
        // top serif at scale 2: y[76,77]
        assertRunPresent(r.inkRuns(), 100, 76, 200, 109, 77, 200);
        assertEquals(15 * 4, r.inkBlockCount());
    }

    @Test
    void backgroundBoxCoversTheWholeCellNotJustInk() {
        // "I" has blank corners (only the stem lights rows 1-5), but the reported bounding box -
        // what a `background` fill would cover - is still the full 5x7 (or scaled) cell.
        TextExpand.Result r = TextExpand.expand("I", POS, "south", 1, 1);
        long cellVolume = (long) r.widthBlocks() * r.heightBlocks();
        assertEquals(5L * 7L, cellVolume);
        assertArrayEquals(new int[] {100, 64, 200}, r.bboxMin());
        assertArrayEquals(new int[] {104, 70, 200}, r.bboxMax());
        // ink is strictly less than the full cell (the corners around the stem are blank)
        assertEquals(15, r.inkBlockCount());
        assertTrue(r.inkBlockCount() < cellVolume);
    }

    @Test
    void invalidFacingThrows() {
        assertThrows(IllegalArgumentException.class, () -> TextExpand.expand("I", POS, "sideways", 1, 1));
    }

    @Test
    void scaleOutOfRangeThrows() {
        assertThrows(IllegalArgumentException.class, () -> TextExpand.expand("I", POS, "south", 5, 1));
        assertThrows(IllegalArgumentException.class, () -> TextExpand.expand("I", POS, "south", 0, 1));
    }

    @Test
    void spacingOutOfRangeThrows() {
        assertThrows(IllegalArgumentException.class, () -> TextExpand.expand("I", POS, "south", 1, 4));
        assertThrows(IllegalArgumentException.class, () -> TextExpand.expand("I", POS, "south", 1, -1));
    }

    private static void assertRunPresent(List<TextExpand.Run> runs, int x1, int y1, int z1, int x2, int y2, int z2) {
        TextExpand.Run expected = new TextExpand.Run(x1, y1, z1, x2, y2, z2);
        for (TextExpand.Run run : runs) {
            if (run.equals(expected)) {
                return;
            }
        }
        throw new AssertionError("expected run " + expected + " not found in " + runs);
    }
}
