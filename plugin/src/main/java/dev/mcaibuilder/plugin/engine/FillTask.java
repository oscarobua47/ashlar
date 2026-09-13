// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.List;

/**
 * Executes a {@code fill_batch} request: a sequence of {@link FillOp}s run
 * in order (plan.md &sect;2.3 point 5: "clear first, then fill" must work), each
 * respecting its {@link FillMode} and optional {@code filter}.
 *
 * <p>Cursor state ({@code opIndex}, {@code cursorY}, {@code cursorZ}) lets
 * {@link #step} resume mid-region on the next tick. Per op, a full row of x
 * is always processed before the tick deadline is checked.
 */
public final class FillTask extends BuildTask {

    private static final BlockData AIR = Material.AIR.createBlockData();

    private final List<FillOp> ops;
    private final World world;
    private final long[] opChanged;

    private int opIndex = 0;
    private int cursorY;
    private int cursorZ;
    private boolean cursorInitialized = false;

    public FillTask(Region region, List<FillOp> ops, World world) {
        super(region);
        this.ops = ops;
        this.world = world;
        this.opChanged = new long[ops.size()];
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        long total = 0;
        for (FillOp op : ops) {
            total += op.region().volume();
        }
        return total;
    }

    @Override
    public boolean step(long deadlineNanos) {
        while (opIndex < ops.size()) {
            FillOp op = ops.get(opIndex);
            Region r = op.region();
            if (!cursorInitialized) {
                cursorY = r.minY();
                cursorZ = r.minZ();
                cursorInitialized = true;
            }
            while (cursorY <= r.maxY()) {
                while (cursorZ <= r.maxZ()) {
                    long rowChanged = processRow(op, r, cursorY, cursorZ);
                    opChanged[opIndex] += rowChanged;
                    addChanged(rowChanged);
                    advance(r.maxX() - r.minX() + 1L);
                    cursorZ++;
                    // Checked once per row of x, per plan.md 2.1/2.3.
                    if (System.nanoTime() >= deadlineNanos) {
                        return false;
                    }
                }
                cursorZ = r.minZ();
                cursorY++;
            }
            opIndex++;
            cursorInitialized = false;
        }
        return true;
    }

    /** Processes one row (fixed y, z; x from minX to maxX). Returns the number of blocks actually written. */
    private long processRow(FillOp op, Region r, int y, int z) {
        boolean edgeRow = (y == r.minY() || y == r.maxY() || z == r.minZ() || z == r.maxZ());
        long changed = 0;
        for (int x = r.minX(); x <= r.maxX(); x++) {
            boolean isShell = edgeRow || x == r.minX() || x == r.maxX();
            BlockData target = switch (op.mode()) {
                case REPLACE, KEEP -> op.block();
                case OUTLINE -> isShell ? op.block() : null;
                case HOLLOW -> isShell ? op.block() : AIR;
            };
            if (target == null) {
                // OUTLINE interior: not part of the shell, leave untouched.
                continue;
            }

            Block block = world.getBlockAt(x, y, z);
            BlockData current = block.getBlockData();

            if (op.mode() == FillMode.KEEP && !current.getMaterial().isAir()) {
                continue;
            }
            if (op.filter() != null && !current.matches(op.filter())) {
                continue;
            }
            if (!current.equals(target)) {
                // The only block-writing call allowed anywhere: never triggers physics.
                block.setBlockData(target, false);
                changed++;
            }
        }
        return changed;
    }

    @Override
    public JsonElement buildResult(long elapsedMs) {
        JsonObject result = new JsonObject();
        result.addProperty("world", world.getName());
        JsonArray opsJson = new JsonArray();
        long totalVolume = 0;
        for (int i = 0; i < ops.size(); i++) {
            long vol = ops.get(i).region().volume();
            totalVolume += vol;
            JsonObject opJson = new JsonObject();
            opJson.addProperty("index", i);
            opJson.addProperty("volume", vol);
            opJson.addProperty("changed", opChanged[i]);
            opsJson.add(opJson);
        }
        result.add("ops", opsJson);
        result.addProperty("totalVolume", totalVolume);
        result.addProperty("totalChanged", changed());
        result.addProperty("elapsedMs", elapsedMs);
        return result;
    }
}
