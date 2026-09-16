// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a {@code restore} request: replays a {@link RegionData}'s runs
 * back onto the world in the same {@code y,z,x} order they were encoded in
 * (plan.md &sect;3.1/&sect;3.3). Decoding is purely sequential, so no random
 * access into the runs is needed.
 *
 * <p>{@code paletteBlocks} (one {@link BlockData} per palette entry) must be
 * parsed by the caller <em>before</em> constructing this task - plan.md
 * &sect;3.1: "if one palette entry fails to parse, the whole restore is
 * rejected", which only works if parsing happens up front, not lazily
 * mid-task. Only cells whose current block differs from the target are
 * written (same no-op-write-avoidance as {@link FillTask}/{@link SparseTask}).
 */
public final class RestoreTask extends BuildTask {

    private static final int DEADLINE_CHECK_INTERVAL = 256;

    private final World world;
    private final RegionData data;
    private final BlockData[] paletteBlocks;
    private final List<int[]> connectablePositions = new ArrayList<>();
    private final ConnectionPass connectionPass;
    private final List<int[]> supportPositions = new ArrayList<>();
    private final NeighbourPositions neighbourPositions = new NeighbourPositions();
    private final SupportCheck supportCheck;

    private int runIndexCursor = 0;
    private int posInRun = 0;
    private int cursorX;
    private int cursorY;
    private int cursorZ;
    private boolean cursorInitialized = false;
    private boolean neighbourPositionsBuilt = false;

    // Note: snapshots do not capture block-entity data (sign text among it),
    // per plan.md/step4d-prompt.md Fix 3 - restoring sign text is v1.1. A
    // restored sign block therefore comes back blank, same as before Fix 3.
    public RestoreTask(Region region, World world, RegionData data, BlockData[] paletteBlocks, boolean connect,
            boolean supportWarnings) {
        super(region);
        this.world = world;
        this.data = data;
        this.paletteBlocks = paletteBlocks;
        this.connectionPass = new ConnectionPass(world, connectablePositions, connect);
        this.supportCheck = new SupportCheck(world, supportPositions, neighbourPositions.positions(), supportWarnings, false);
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return data.volume();
    }

    @Override
    public boolean step(long deadlineNanos) {
        Region r = region();
        if (!neighbourPositionsBuilt) {
            // docs/prompts/step4i-prompt.md: unlike FillTask/SparseTask, a restore's runs do not map
            // to clean per-op rectangles worth checking individually for "is this run's target
            // non-solid" - a restore can turn any part of the region back to air, so just add the one
            // shell around the whole snapshot region, unconditionally (purely geometric; cheapest done
            // once, up front, rather than threaded through the run-decoding cursor below).
            neighbourPositions.addShell(r, world.getMinHeight(), world.getMaxHeight());
            neighbourPositionsBuilt = true;
        }
        if (!cursorInitialized) {
            cursorX = r.minX();
            cursorY = r.minY();
            cursorZ = r.minZ();
            cursorInitialized = true;
        }
        int[] runIndex = data.runIndex();
        int[] runLength = data.runLength();
        int sinceCheck = 0;
        while (runIndexCursor < runIndex.length) {
            BlockData target = paletteBlocks[runIndex[runIndexCursor]];
            while (posInRun < runLength[runIndexCursor]) {
                Block block = world.getBlockAt(cursorX, cursorY, cursorZ);
                BlockData current = block.getBlockData();
                if (!current.equals(target)) {
                    // The only block-writing call allowed anywhere: never triggers physics.
                    block.setBlockData(target, false);
                    addChanged(1);
                    if (ConnectionPass.isConnectable(target)) {
                        connectablePositions.add(new int[]{cursorX, cursorY, cursorZ});
                    }
                    if (SupportCheck.needsCheck(target, false)) {
                        supportPositions.add(new int[]{cursorX, cursorY, cursorZ});
                    }
                }
                advance(1);
                posInRun++;
                advanceCursor(r);
                sinceCheck++;
                if (sinceCheck >= DEADLINE_CHECK_INTERVAL) {
                    sinceCheck = 0;
                    boolean moreWork = posInRun < runLength[runIndexCursor] || runIndexCursor + 1 < runIndex.length;
                    if (moreWork && System.nanoTime() >= deadlineNanos) {
                        return false;
                    }
                }
            }
            posInRun = 0;
            runIndexCursor++;
        }
        // Main cursor is done; spend any remaining tick budget on the connection
        // pass (Fix 2) and then the support check (step4h-prompt.md), each
        // resumable across ticks exactly like the loop above.
        if (!connectionPass.step(deadlineNanos)) {
            return false;
        }
        return supportCheck.step(deadlineNanos);
    }

    /** Moves (x,y,z) to the next cell in y-outer/z-middle/x-inner order, matching the encoder's traversal. */
    private void advanceCursor(Region r) {
        cursorX++;
        if (cursorX > r.maxX()) {
            cursorX = r.minX();
            cursorZ++;
            if (cursorZ > r.maxZ()) {
                cursorZ = r.minZ();
                cursorY++;
            }
        }
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        JsonObject result = new JsonObject();
        result.addProperty("restored", changed());
        result.addProperty("volume", volume());
        result.addProperty("queuedMs", queuedMs);
        result.addProperty("elapsedMs", elapsedMs);
        SupportWarnings.addTo(result, supportCheck, neighbourPositions.truncated());
        return result;
    }
}
