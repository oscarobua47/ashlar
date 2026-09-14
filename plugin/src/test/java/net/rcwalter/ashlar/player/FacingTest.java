// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link Facing} (plan.md &sect;4.5.1): the yaw-to-cardinal
 * mapping at the four cardinal angles, plus the negative and out-of-range
 * boundary cases called out in the step prompt.
 */
class FacingTest {

    @Test
    void cardinalAngles() {
        assertEquals("south", Facing.fromYaw(0f));
        assertEquals("west", Facing.fromYaw(90f));
        assertEquals("north", Facing.fromYaw(180f));
        assertEquals("east", Facing.fromYaw(270f));
    }

    @Test
    void negativeYawWrapsToEast() {
        // -90 is equivalent to 270 after normalizing to [0,360).
        assertEquals("east", Facing.fromYaw(-90f));
    }

    @Test
    void outOfRangeYawWrapsToSouth() {
        // 359.9 normalizes to just under 360, closest to 360 % 360 = 0 = south.
        assertEquals("south", Facing.fromYaw(359.9f));
    }

    @Test
    void diagonalYawRoundsUpToNextQuadrant() {
        assertEquals("west", Facing.fromYaw(45f));
        assertEquals("north", Facing.fromYaw(135f));
    }

    @Test
    void offsetMatchesEachFacing() {
        assertArrayEquals(new int[] {0, 0, 1}, Facing.offset("south"));
        assertArrayEquals(new int[] {-1, 0, 0}, Facing.offset("west"));
        assertArrayEquals(new int[] {0, 0, -1}, Facing.offset("north"));
        assertArrayEquals(new int[] {1, 0, 0}, Facing.offset("east"));
    }
}
