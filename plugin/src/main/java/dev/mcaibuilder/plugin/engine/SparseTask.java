// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.List;

/**
 * Executes a {@code set_blocks} request: a sparse list of coordinate/block
 * pairs, written in order. Unlike {@link FillTask} there is no "row" concept,
 * so the tick deadline is simply checked after every write.
 */
public final class SparseTask extends BuildTask {

    private final List<SparseOp> ops;
    private final World world;
    private int index = 0;

    public SparseTask(Region region, List<SparseOp> ops, World world) {
        super(region);
        this.ops = ops;
        this.world = world;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return ops.size();
    }

    @Override
    public boolean step(long deadlineNanos) {
        while (index < ops.size()) {
            SparseOp op = ops.get(index);
            BlockData target = op.block();
            Block block = world.getBlockAt(op.x(), op.y(), op.z());
            BlockData current = block.getBlockData();
            if (!current.equals(target)) {
                // The only block-writing call allowed anywhere: never triggers physics.
                block.setBlockData(target, false);
                addChanged(1);
            }
            index++;
            advance(1);
            if (index < ops.size() && System.nanoTime() >= deadlineNanos) {
                return false;
            }
        }
        return true;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        JsonObject result = new JsonObject();
        result.addProperty("world", world.getName());
        result.addProperty("requested", ops.size());
        result.addProperty("changed", changed());
        result.addProperty("queuedMs", queuedMs);
        result.addProperty("elapsedMs", elapsedMs);
        return result;
    }
}
