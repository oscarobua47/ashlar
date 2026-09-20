// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.block.data.type.Wall;

import java.util.List;

/**
 * Post-pass that gives freshly-written connectable blocks (glass panes,
 * iron bars, fences, vines/chorus via {@link MultipleFacing}, {@link Wall},
 * {@link Stairs} corners, {@link RedstoneWire}, {@link Tripwire}) their
 * correct connected shape, the way hand-placing or {@code /setblock} with an
 * explicit connected state would. Runs after a {@link FillTask}/{@link
 * SparseTask}/{@link RestoreTask}'s main cursor completes, budgeted through
 * the same {@link TickBudgetExecutor} tick deadline as the rest of the task
 * (docs/prompts/step4d-prompt.md Fix 2).
 *
 * <p><b>Why this does an air-then-back round trip instead of a plain
 * re-apply.</b> The step4d Fix 2 experiment (run against the live test
 * server before this class was written, using a temporary debug RPC handler
 * since removed) first tried the naive fix: {@code
 * block.setBlockData(block.getBlockData(), true)}, i.e. re-applying a
 * block's own already-stored data with {@code physics=true}. That is a total
 * no-op - Paper/NMS skips the write because the new state equals the one
 * already stored (confirmed with a second, independent check: re-applying a
 * floating sand block's own data that same way did not make it fall
 * either). Only a genuine state transition fires the engine's shape/physics
 * update. The verified working sequence, confirmed experimentally, is:
 * clear the cell to air with {@code physics=false} (no side effects while
 * briefly air), then write the real target block back with {@code
 * physics=true} - that second write is what actually fires the
 * neighbor-notification chain. That notification only updates the
 * *neighbors* of whichever cell was just (re)written, never the rewritten
 * cell's own shape towards neighbors that were already there - so of three
 * panes in a row, freshly re-writing each one (in this class, the "self"
 * phase) connects them all to each other (each rewrite notifies the pane(s)
 * next to it), but a pane next to an untouched stone wall stays
 * disconnected from the stone until the stone is *also* freshly
 * re-applied - verified experimentally: re-touching only the panes left the
 * stone-facing side disconnected ({@code west=false}); additionally
 * re-touching the stone connected it ({@code west=true}). Hence the two
 * phases below: every tracked position gets the air-then-back treatment for
 * itself, then again for its four horizontal neighbors.
 *
 * <p><b>Neighbor safety.</b> The air-then-back round trip is only safe to
 * perform on a neighbor block that has no state worth losing during its
 * brief moment as air. Per the step4d prompt, gravity blocks and liquids are
 * skipped so sand/gravel never fall and water never flows because of this
 * pass. This class additionally skips any neighbor holding block-entity data
 * ({@link TileState}: chests, signs, spawners, furnaces, ...) - the prompt's
 * literal re-apply-own-data instruction would not have touched those at
 * all, but since that approach turned out to be a no-op, the air round trip
 * is required instead, and briefly turning a chest to air would destroy its
 * inventory. This extra skip is a deviation beyond the prompt's explicit
 * gravity/liquid list, added for safety; see the step4d report. Skipping
 * those cells is still not enough on its own, because the physics=true
 * write of any <em>other</em> cell notifies them too: see {@link
 * #neighboursStayPut}, which skips a refresh entirely when a neighbouring
 * gravity block or liquid would actually move because of it.
 */
final class ConnectionPass {

    /** How many cells are processed between deadline checks, within either phase. */
    private static final int DEADLINE_CHECK_INTERVAL = 64;

    private static final BlockData AIR = Material.AIR.createBlockData();

    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    /** The six face neighbours checked by {@link #neighboursStayPut}. */
    private static final int[] NX = {1, -1, 0, 0, 0, 0};
    private static final int[] NY = {0, 0, 0, 0, 1, -1};
    private static final int[] NZ = {0, 0, 1, -1, 0, 0};

    private final World world;
    private final List<int[]> positions;
    private final boolean enabled;

    private boolean selfPhaseDone;
    private int selfIndex = 0;
    private int neighborIndex = 0;
    private int neighborSide = 0;

    ConnectionPass(World world, List<int[]> positions, boolean enabled) {
        this.world = world;
        this.positions = positions;
        this.enabled = enabled;
        // Callers construct this before their write loop fills `positions`, so
        // the list is always empty here; decide emptiness in step(), not now.
        this.selfPhaseDone = false;
    }

    /** Whether {@code data}'s shape depends on its neighbors and so needs a connection-pass entry. */
    static boolean isConnectable(BlockData data) {
        return data instanceof MultipleFacing
                || data instanceof Wall
                || data instanceof Stairs
                || data instanceof RedstoneWire
                || data instanceof Tripwire;
    }

    /** Does as much work as fits before {@code deadlineNanos}; returns {@code true} once fully done. */
    boolean step(long deadlineNanos) {
        if (!enabled || positions.isEmpty()) {
            return true;
        }
        if (!selfPhaseDone && !runSelfPhase(deadlineNanos)) {
            return false;
        }
        return runNeighborPhase(deadlineNanos);
    }

    private boolean runSelfPhase(long deadlineNanos) {
        int sinceCheck = 0;
        while (selfIndex < positions.size()) {
            int[] pos = positions.get(selfIndex);
            refresh(pos[0], pos[1], pos[2]);
            selfIndex++;
            sinceCheck++;
            if (sinceCheck >= DEADLINE_CHECK_INTERVAL) {
                sinceCheck = 0;
                if (selfIndex < positions.size() && System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
        }
        selfPhaseDone = true;
        return true;
    }

    private boolean runNeighborPhase(long deadlineNanos) {
        int sinceCheck = 0;
        while (neighborIndex < positions.size()) {
            int[] pos = positions.get(neighborIndex);
            while (neighborSide < DX.length) {
                refreshNeighborIfSafe(pos[0] + DX[neighborSide], pos[1], pos[2] + DZ[neighborSide]);
                neighborSide++;
                sinceCheck++;
                if (sinceCheck >= DEADLINE_CHECK_INTERVAL) {
                    sinceCheck = 0;
                    boolean moreWork = neighborSide < DX.length || neighborIndex + 1 < positions.size();
                    if (moreWork && System.nanoTime() >= deadlineNanos) {
                        return false;
                    }
                }
            }
            neighborSide = 0;
            neighborIndex++;
        }
        return true;
    }

    /**
     * Clears {@code (x,y,z)} to air (physics=false) then writes its current data back
     * (physics=true) - unless a block next to it would be moved by that physics write.
     */
    private void refresh(int x, int y, int z) {
        if (!neighboursStayPut(x, y, z)) {
            return;
        }
        Block block = world.getBlockAt(x, y, z);
        BlockData target = block.getBlockData();
        block.setBlockData(AIR, false);
        block.setBlockData(target, true);
    }

    /**
     * Whether the physics=true write in {@link #refresh} is safe for the six neighbours of
     * {@code (x,y,z)}. That write fires {@code updateShape} on every neighbour; for sand,
     * gravel, concrete powder and the like that schedules a fall, and for water/lava a
     * fluid tick - both run as soon as the chunk ticks (a player nearby, or the executor's
     * post-task ticket hold). Skipping gravity/liquid cells in the neighbour phase is not
     * enough: verified on the test server (force-loaded chunk, sand and a fence written in
     * one batch), the fence's own refresh made the unsupported sand fall. So a cell is only
     * refreshed when no neighbouring gravity block is unsupported and no neighbouring liquid
     * has anywhere to flow; a supported sand block or an enclosed pond is unaffected by the
     * scheduled tick, and their neighbours still get their shape.
     */
    private boolean neighboursStayPut(int x, int y, int z) {
        for (int i = 0; i < NX.length; i++) {
            int nx = x + NX[i];
            int ny = y + NY[i];
            int nz = z + NZ[i];
            Material m = world.getBlockAt(nx, ny, nz).getType();
            if (m.hasGravity()) {
                if (isFree(nx, ny - 1, nz)) {
                    return false;
                }
            } else if (isLiquid(m)) {
                if (isFree(nx, ny - 1, nz) || isFree(nx + 1, ny, nz) || isFree(nx - 1, ny, nz)
                        || isFree(nx, ny, nz + 1) || isFree(nx, ny, nz - 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether sand could fall into, or water flow into, {@code (x,y,z)}: air, or anything
     * neither solid nor liquid (grass, flowers, snow layers - vanilla's "replaceable" set is
     * a subset of that, so this errs towards skipping a refresh).
     */
    private boolean isFree(int x, int y, int z) {
        Material m = world.getBlockAt(x, y, z).getType();
        return m.isAir() || (!m.isSolid() && !isLiquid(m));
    }

    private void refreshNeighborIfSafe(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        Material material = block.getType();
        if (material == Material.AIR) {
            return;
        }
        if (material.hasGravity() || isLiquid(material)) {
            return; // Never physics-update sand/gravel/water because of this pass.
        }
        if (block.getState() instanceof TileState) {
            return; // Never destroy a block entity's data via the air round trip.
        }
        refresh(x, y, z);
    }

    /**
     * {@link Material} has no {@code isLiquid()} in this Paper API version;
     * water/lava/bubble columns are the only block materials that behave as
     * a liquid for physics purposes.
     */
    private static boolean isLiquid(Material material) {
        return material == Material.WATER || material == Material.LAVA || material == Material.BUBBLE_COLUMN;
    }
}
