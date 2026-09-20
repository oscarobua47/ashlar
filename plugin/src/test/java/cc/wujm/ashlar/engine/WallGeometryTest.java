// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WallGeometry}, the pure-geometry predicate behind
 * {@link FillMode#WALLS} (Fix 1, docs/prompts/step4d-prompt.md). Pure Java,
 * no Bukkit.
 */
class WallGeometryTest {

    @Test
    void cornersAndEdgesAreWallCells() {
        Region region = new Region(0, 64, 0, 6, 67, 6); // 7x4x7

        assertTrue(WallGeometry.isWallCell(0, 0, region), "min corner");
        assertTrue(WallGeometry.isWallCell(6, 6, region), "max corner");
        assertTrue(WallGeometry.isWallCell(3, 0, region), "min-z edge");
        assertTrue(WallGeometry.isWallCell(3, 6, region), "max-z edge");
        assertTrue(WallGeometry.isWallCell(0, 3, region), "min-x edge");
        assertTrue(WallGeometry.isWallCell(6, 3, region), "max-x edge");
    }

    @Test
    void interiorCellsAreNotWallCells() {
        Region region = new Region(0, 64, 0, 6, 67, 6); // 7x4x7

        assertFalse(WallGeometry.isWallCell(1, 1, region));
        assertFalse(WallGeometry.isWallCell(3, 3, region), "center");
        assertFalse(WallGeometry.isWallCell(5, 5, region));
    }

    @Test
    void isIndependentOfY() {
        // WALLS applies the same x/z predicate at every y - the whole point of
        // Fix 1 is that the floor/ceiling layers are untouched, unlike HOLLOW.
        Region region = new Region(10, -5, 10, 12, 10, 12);

        for (int y = region.minY(); y <= region.maxY(); y++) {
            assertTrue(WallGeometry.isWallCell(10, 10, region), "corner at y=" + y);
            assertFalse(WallGeometry.isWallCell(11, 11, region), "center at y=" + y);
        }
    }

    @Test
    void sevenBySevenFootprintPerLayerRingCountMatchesPromptExample() {
        // "walls 7x4x7 -> changed equals 4 sides count (2*7*4 + 2*5*4 = 96
        // for a 7x7 footprint, 4 tall)" - i.e. a 7x7 footprint's ring is
        // 2*7 + 2*5 = 24 cells; 4 layers tall -> 96 total wall cells.
        Region region = new Region(100, 70, 100, 106, 73, 106); // 7x4x7
        int ringCellsPerLayer = 0;
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int z = region.minZ(); z <= region.maxZ(); z++) {
                if (WallGeometry.isWallCell(x, z, region)) {
                    ringCellsPerLayer++;
                }
            }
        }
        assertEquals(24, ringCellsPerLayer);
        int layers = region.maxY() - region.minY() + 1;
        assertEquals(4, layers);
        assertEquals(96, ringCellsPerLayer * layers);
    }
}
