// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static cc.wujm.ashlar.engine.ChestPairing.ChestHalf;
import static cc.wujm.ashlar.engine.ChestPairing.Direction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ChestPairing}, the pure direction arithmetic and pairing decision behind
 * {@link ChestPairPass} (step8e-prompt.md). Pure Java, no Bukkit - a small in-memory {@code [x,z]}
 * grid stands in for the world.
 */
class ChestPairingTest {

    // ------------------------------------------------------------------
    // Direction arithmetic
    // ------------------------------------------------------------------

    @Test
    void clockwiseCyclesNorthEastSouthWestNorth() {
        assertEquals(Direction.EAST, ChestPairing.clockwise(Direction.NORTH));
        assertEquals(Direction.SOUTH, ChestPairing.clockwise(Direction.EAST));
        assertEquals(Direction.WEST, ChestPairing.clockwise(Direction.SOUTH));
        assertEquals(Direction.NORTH, ChestPairing.clockwise(Direction.WEST));
    }

    @Test
    void counterClockwiseIsTheReverseCycle() {
        assertEquals(Direction.WEST, ChestPairing.counterClockwise(Direction.NORTH));
        assertEquals(Direction.NORTH, ChestPairing.counterClockwise(Direction.EAST));
        assertEquals(Direction.EAST, ChestPairing.counterClockwise(Direction.SOUTH));
        assertEquals(Direction.SOUTH, ChestPairing.counterClockwise(Direction.WEST));
    }

    @Test
    void offsetsMatchCompassDirections() {
        assertEquals(0, ChestPairing.dx(Direction.NORTH));
        assertEquals(-1, ChestPairing.dz(Direction.NORTH));
        assertEquals(1, ChestPairing.dx(Direction.EAST));
        assertEquals(0, ChestPairing.dz(Direction.EAST));
        assertEquals(0, ChestPairing.dx(Direction.SOUTH));
        assertEquals(1, ChestPairing.dz(Direction.SOUTH));
        assertEquals(-1, ChestPairing.dx(Direction.WEST));
        assertEquals(0, ChestPairing.dz(Direction.WEST));
    }

    // ------------------------------------------------------------------
    // Pairing decision, over a small in-memory [x,z] grid
    // ------------------------------------------------------------------

    /** A tiny world: chest cells keyed by "x,z" (single y layer is enough for this pure logic). */
    private static final class Grid {
        private final Map<String, ChestPairing.Cell> cells = new HashMap<>();

        void put(int x, int z, ChestPairing.Cell cell) {
            cells.put(x + "," + z, cell);
        }

        ChestPairing.Cell get(int x, int z) {
            return cells.get(x + "," + z);
        }

        /** Looks up the cell one step from (x,z) in {@code direction} - the lambda {@link ChestPairing#decide} takes. */
        ChestPairing.Cell neighborOf(int x, int z, Direction direction) {
            return get(x + ChestPairing.dx(direction), z + ChestPairing.dz(direction));
        }
    }

    @Test
    void pairsClockwiseFirstWhenBothSidesWouldQualify() {
        // Two chests north-facing, one at (0,0), one at its clockwise (east) neighbour (1,0), AND
        // another pairable one at its counter-clockwise (west) neighbour (-1,0): clockwise wins.
        Grid grid = new Grid();
        grid.put(0, 0, new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.SINGLE));
        grid.put(1, 0, new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.SINGLE));
        grid.put(-1, 0, new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.SINGLE));

        ChestPairing.Cell self = grid.get(0, 0);
        ChestPairing.Decision decision = ChestPairing.decide(self, dir -> grid.neighborOf(0, 0, dir));

        assertEquals(new ChestPairing.Decision(Direction.EAST, ChestHalf.LEFT, ChestHalf.RIGHT), decision);
    }

    @Test
    void fallsBackToCounterClockwiseWhenClockwiseDoesNotPair() {
        // Only the west (counter-clockwise of north) neighbour is a pairable single chest.
        Grid grid = new Grid();
        grid.put(0, 0, new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.SINGLE));
        grid.put(-1, 0, new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.SINGLE));
        // (1,0), the clockwise side, has no chest at all.

        ChestPairing.Cell self = grid.get(0, 0);
        ChestPairing.Decision decision = ChestPairing.decide(self, dir -> grid.neighborOf(0, 0, dir));

        assertEquals(new ChestPairing.Decision(Direction.WEST, ChestHalf.RIGHT, ChestHalf.LEFT), decision);
    }

    @Test
    void noNeighbourOnEitherSideYieldsNoDecision() {
        ChestPairing.Cell self = new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.SINGLE);
        assertNull(ChestPairing.decide(self, dir -> null));
    }

    @Test
    void differentFacingDoesNotPair() {
        Grid grid = new Grid();
        grid.put(0, 0, new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.SINGLE));
        grid.put(1, 0, new ChestPairing.Cell(false, Direction.EAST, ChestHalf.SINGLE)); // clockwise side, wrong facing
        grid.put(-1, 0, new ChestPairing.Cell(false, Direction.SOUTH, ChestHalf.SINGLE)); // counter-clockwise side, wrong facing

        ChestPairing.Cell self = grid.get(0, 0);
        assertNull(ChestPairing.decide(self, dir -> grid.neighborOf(0, 0, dir)));
    }

    @Test
    void differentMaterialDoesNotPair() {
        // A normal chest never pairs with a trapped chest, even with matching facing.
        Grid grid = new Grid();
        grid.put(0, 0, new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.SINGLE));
        grid.put(1, 0, new ChestPairing.Cell(true, Direction.NORTH, ChestHalf.SINGLE));

        ChestPairing.Cell self = grid.get(0, 0);
        assertNull(ChestPairing.decide(self, dir -> grid.neighborOf(0, 0, dir)));
    }

    @Test
    void neighborAlreadyPairedDoesNotPairAgain() {
        // Neighbour is already the LEFT half of some other double chest - not SINGLE, so it is skipped.
        Grid grid = new Grid();
        grid.put(0, 0, new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.SINGLE));
        grid.put(1, 0, new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.LEFT));

        ChestPairing.Cell self = grid.get(0, 0);
        assertNull(ChestPairing.decide(self, dir -> grid.neighborOf(0, 0, dir)));
    }

    @Test
    void selfAlreadyPairedIsNeverReconsidered() {
        ChestPairing.Cell self = new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.RIGHT);
        assertNull(ChestPairing.decide(self, dir -> new ChestPairing.Cell(false, Direction.NORTH, ChestHalf.SINGLE)));
    }

    @Test
    void nullSelfYieldsNoDecision() {
        assertNull(ChestPairing.decide(null, dir -> null));
    }

    @Test
    void everyCardinalFacingPairsWithItsClockwiseNeighbour() {
        for (Direction facing : Direction.values()) {
            Direction cw = ChestPairing.clockwise(facing);
            Grid grid = new Grid();
            grid.put(0, 0, new ChestPairing.Cell(false, facing, ChestHalf.SINGLE));
            grid.put(ChestPairing.dx(cw), ChestPairing.dz(cw), new ChestPairing.Cell(false, facing, ChestHalf.SINGLE));

            ChestPairing.Cell self = grid.get(0, 0);
            ChestPairing.Decision decision = ChestPairing.decide(self, dir -> grid.neighborOf(0, 0, dir));

            assertEquals(new ChestPairing.Decision(cw, ChestHalf.LEFT, ChestHalf.RIGHT), decision, "facing=" + facing);
        }
    }
}
