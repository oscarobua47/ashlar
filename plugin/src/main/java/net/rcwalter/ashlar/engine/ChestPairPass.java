// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;

import java.util.List;

/**
 * Post-pass (step8e-prompt.md) that pairs two freshly-written adjacent single chests into a
 * double chest, the way vanilla's placement logic does but {@link ConnectionPass} deliberately
 * never does - it skips block entities entirely (step4d-prompt.md), so two hand-written chests
 * side by side used to stay {@code type=single} forever. The direction arithmetic and pairing
 * decision live in {@link ChestPairing}, a pure function unit-tested without Bukkit; this class
 * only adapts real {@code BlockData} reads/writes to it.
 *
 * <p>Runs only when {@code engine.connect-blocks} is on (it is a connection pass in spirit),
 * budgeted through the same tick deadline as {@link ConnectionPass}/{@link SupportCheck} in the
 * owning task's {@code step()} (same place in {@link FillTask}/{@link SparseTask}). Unlike {@link
 * ConnectionPass} there is no air-then-back round trip here: only the chest's {@code type}
 * property changes, via a single {@code setBlockData(data, false)} - verified on the test server
 * (step8e report) that this preserves the chest's inventory.
 */
final class ChestPairPass {

    /** How many candidate positions are examined between deadline checks. */
    private static final int DEADLINE_CHECK_INTERVAL = 64;

    private final World world;
    private final List<int[]> positions;
    private final boolean enabled;

    private int index = 0;
    private int pairsMade = 0;

    ChestPairPass(World world, List<int[]> positions, boolean enabled) {
        this.world = world;
        this.positions = positions;
        this.enabled = enabled;
    }

    /** Whether {@code data} is a block this pass ever looks at (chest or trapped_chest). */
    static boolean isChest(BlockData data) {
        return data instanceof Chest;
    }

    /** Does as much work as fits before {@code deadlineNanos}; returns {@code true} once fully done. */
    boolean step(long deadlineNanos) {
        if (!enabled || positions.isEmpty()) {
            return true;
        }
        int sinceCheck = 0;
        while (index < positions.size()) {
            int[] pos = positions.get(index);
            tryPair(pos[0], pos[1], pos[2]);
            index++;
            sinceCheck++;
            if (sinceCheck >= DEADLINE_CHECK_INTERVAL) {
                sinceCheck = 0;
                if (index < positions.size() && System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Number of pairs formed so far; surfaced as {@code chestsPaired} in the task result JSON. */
    int pairsMade() {
        return pairsMade;
    }

    private void tryPair(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        BlockData data = block.getBlockData();
        if (!(data instanceof Chest chest)) {
            // Overwritten by a later op in the same batch, or (more commonly) already paired
            // because an earlier written position turned out to be its own clockwise/
            // counter-clockwise partner - each position is handled exactly once regardless.
            return;
        }
        ChestPairing.Cell self = toCell(block.getType(), chest);
        if (self == null) {
            return;
        }
        ChestPairing.Decision decision = ChestPairing.decide(self, direction -> {
            Block neighbor = block.getRelative(ChestPairing.dx(direction), 0, ChestPairing.dz(direction));
            if (!(neighbor.getBlockData() instanceof Chest neighborChest)) {
                return null;
            }
            return toCell(neighbor.getType(), neighborChest);
        });
        if (decision == null) {
            return;
        }
        Block neighbor = block.getRelative(ChestPairing.dx(decision.side()), 0, ChestPairing.dz(decision.side()));
        chest.setType(toBukkitType(decision.selfHalf()));
        block.setBlockData(chest, false);
        Chest neighborChest = (Chest) neighbor.getBlockData();
        neighborChest.setType(toBukkitType(decision.neighborHalf()));
        neighbor.setBlockData(neighborChest, false);
        pairsMade++;
    }

    /** {@code null} for a facing this pass never sees in practice (a chest always faces a cardinal direction). */
    private static ChestPairing.Cell toCell(Material material, Chest chest) {
        ChestPairing.Direction facing = toDirection(chest.getFacing());
        if (facing == null) {
            return null;
        }
        boolean trapped = material == Material.TRAPPED_CHEST;
        return new ChestPairing.Cell(trapped, facing, toHalf(chest.getType()));
    }

    private static ChestPairing.Direction toDirection(BlockFace face) {
        return switch (face) {
            case NORTH -> ChestPairing.Direction.NORTH;
            case EAST -> ChestPairing.Direction.EAST;
            case SOUTH -> ChestPairing.Direction.SOUTH;
            case WEST -> ChestPairing.Direction.WEST;
            default -> null;
        };
    }

    private static ChestPairing.ChestHalf toHalf(Chest.Type type) {
        return switch (type) {
            case SINGLE -> ChestPairing.ChestHalf.SINGLE;
            case LEFT -> ChestPairing.ChestHalf.LEFT;
            case RIGHT -> ChestPairing.ChestHalf.RIGHT;
        };
    }

    private static Chest.Type toBukkitType(ChestPairing.ChestHalf half) {
        return switch (half) {
            case SINGLE -> Chest.Type.SINGLE;
            case LEFT -> Chest.Type.LEFT;
            case RIGHT -> Chest.Type.RIGHT;
        };
    }
}
