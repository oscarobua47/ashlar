// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import java.util.function.Function;

/**
 * Pure direction arithmetic and pairing decision for step8e-prompt.md ("pair adjacent chests into
 * double chests after a build"), kept free of Bukkit types so it is unit-testable over a plain
 * in-memory grid ({@code ChestPairingTest}) without a live Paper block registry. {@link
 * ChestPairPass} is the Bukkit-facing wrapper that adapts real {@code BlockData}/{@code World}
 * reads and writes to this.
 *
 * <p><b>Vanilla rule (step8e-prompt.md, matching Minecraft's own {@code
 * ChestBlock.getConnectedDirection}):</b> for a chest or trapped_chest with {@code type=single}
 * and facing F, the partner of a {@code left} half lies in the direction F rotated clockwise
 * (north-&gt;east-&gt;south-&gt;west-&gt;north); the partner of a {@code right} half lies
 * counter-clockwise. Two chests pair only when they are the same material (chest with chest,
 * trapped with trapped), have the same facing, and are both {@code single}. Verified against real
 * block-entity behaviour on the test server before this class was written (step8e report): writing
 * only a chest's {@code type} property via the same {@code setBlockData(data, false)} call this
 * pass uses does not touch its inventory.
 */
final class ChestPairing {

    private ChestPairing() {
    }

    /** The four horizontal directions a chest can face. */
    enum Direction { NORTH, EAST, SOUTH, WEST }

    /** A chest's connection state: unpaired, or already the left/right half of a double chest. */
    enum ChestHalf { SINGLE, LEFT, RIGHT }

    /** One grid cell's chest state (never constructed for a non-chest cell; callers pass {@code null} instead). */
    record Cell(boolean trapped, Direction facing, ChestHalf half) {
    }

    /** The decision for one candidate cell: which side its partner is on, and what each half becomes. */
    record Decision(Direction side, ChestHalf selfHalf, ChestHalf neighborHalf) {
    }

    static Direction clockwise(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
        };
    }

    static Direction counterClockwise(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.WEST;
            case WEST -> Direction.SOUTH;
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
        };
    }

    /** X offset of one step in {@code direction} (east=+1, west=-1, north/south=0). */
    static int dx(Direction direction) {
        return switch (direction) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
    }

    /** Z offset of one step in {@code direction} (south=+1, north=-1, east/west=0). */
    static int dz(Direction direction) {
        return switch (direction) {
            case SOUTH -> 1;
            case NORTH -> -1;
            default -> 0;
        };
    }

    /**
     * Decides how {@code self} pairs, given a lookup of the neighbouring cell in a given direction
     * ({@code null} when that neighbour holds no chest). Tries the clockwise side first (self
     * becomes {@code LEFT}, neighbor {@code RIGHT}); if that neighbor is not a pairable single
     * chest, tries counter-clockwise (self becomes {@code RIGHT}, neighbor {@code LEFT}). Returns
     * {@code null} when {@code self} is not a single chest, or neither side pairs.
     */
    static Decision decide(Cell self, Function<Direction, Cell> neighborAt) {
        if (self == null || self.half() != ChestHalf.SINGLE) {
            return null;
        }
        Direction cw = clockwise(self.facing());
        if (pairable(self, neighborAt.apply(cw))) {
            return new Decision(cw, ChestHalf.LEFT, ChestHalf.RIGHT);
        }
        Direction ccw = counterClockwise(self.facing());
        if (pairable(self, neighborAt.apply(ccw))) {
            return new Decision(ccw, ChestHalf.RIGHT, ChestHalf.LEFT);
        }
        return null;
    }

    private static boolean pairable(Cell self, Cell neighbor) {
        return neighbor != null
                && neighbor.half() == ChestHalf.SINGLE
                && neighbor.trapped() == self.trapped()
                && neighbor.facing() == self.facing();
    }
}
