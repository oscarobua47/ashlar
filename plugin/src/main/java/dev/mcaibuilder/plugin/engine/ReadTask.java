// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import com.google.gson.JsonElement;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * Executes a {@code read_region} request: scans {@link #region()} in {@code
 * y,z,x} order (plan.md &sect;3.3) and feeds each cell's {@link
 * BlockData#getAsString()} into a {@link RegionData.Encoder}. Also reused by
 * {@code snapshot} (plan.md &sect;3.1): the handler submits a {@code ReadTask}
 * and, once its future completes, reads {@link #regionData()} to build the
 * stored {@link dev.mcaibuilder.plugin.snapshot.Snapshot} instead of using
 * the raw {@code read_region}-shaped JSON this class also produces.
 *
 * <p>{@code changed()} is never incremented; this task is read-only. The
 * tick deadline is checked every {@link #DEADLINE_CHECK_INTERVAL} blocks
 * within a row (same strategy as {@link FillTask}, plan.md &sect;2.6), so a
 * large read cannot stall a tick either.
 */
public final class ReadTask extends BuildTask {

    private static final int DEADLINE_CHECK_INTERVAL = 256;

    private final World world;
    private final RegionData.Encoder encoder = new RegionData.Encoder();

    private int cursorY;
    private int cursorZ;
    private int cursorX;
    private boolean cursorInitialized = false;
    private RegionData result;

    public ReadTask(Region region, World world) {
        super(region);
        this.world = world;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return region().volume();
    }

    @Override
    public boolean step(long deadlineNanos) {
        Region r = region();
        if (!cursorInitialized) {
            cursorY = r.minY();
            cursorZ = r.minZ();
            cursorX = r.minX();
            cursorInitialized = true;
        }
        while (cursorY <= r.maxY()) {
            while (cursorZ <= r.maxZ()) {
                if (!readRowSegment(r, deadlineNanos)) {
                    return false; // deadline hit mid-row; cursorX preserved for resume
                }
                cursorX = r.minX();
                cursorZ++;
                if (System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
            cursorZ = r.minZ();
            cursorY++;
        }
        return true;
    }

    /** Reads blocks from {@code cursorX} to {@code r.maxX()} for the current y/z row. Returns false if the deadline was hit mid-row. */
    private boolean readRowSegment(Region r, long deadlineNanos) {
        int sinceCheck = 0;
        while (cursorX <= r.maxX()) {
            BlockData data = world.getBlockAt(cursorX, cursorY, cursorZ).getBlockData();
            encoder.add(data.getAsString());
            advance(1);
            cursorX++;
            sinceCheck++;
            if (sinceCheck >= DEADLINE_CHECK_INTERVAL) {
                sinceCheck = 0;
                if (cursorX <= r.maxX() && System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        result = encoder.finish(region());
        return result.toJson();
    }

    /** The decoded region data. Only populated once {@link #buildResult} has run. */
    public RegionData regionData() {
        return result;
    }
}
