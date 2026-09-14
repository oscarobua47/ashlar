// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import com.google.gson.JsonElement;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.MainThread;
import org.bukkit.World;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The execution path behind {@code set_blocks} (spec &sect;3.3), split out
 * of {@code SetBlocksHandler} (plan.md step7); see {@link FillService} for
 * the equivalent split of {@code fill_batch}. Callers must have already
 * validated {@code ops} (world, build-region, Y range, block parsing).
 */
public final class SparseService {

    private final PluginConfig config;
    private final TickBudgetExecutor executor;

    public SparseService(PluginConfig config, TickBudgetExecutor executor) {
        this.config = config;
        this.executor = executor;
    }

    /** Enqueues sparse block writes on {@code world}. Must not be called from the main thread. */
    public CompletableFuture<JsonElement> set(World world, List<SparseOp> ops, boolean connect, InvocationContext ctx) {
        MainThread.assertNotPrimary("SparseService.set");
        Region region = boundingRegion(ops);
        SparseTask task = new SparseTask(region, ops, world, connect, config.engine().supportWarnings());
        return executor.submit(task, ctx);
    }

    private static Region boundingRegion(List<SparseOp> ops) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (SparseOp op : ops) {
            minX = Math.min(minX, op.x());
            minY = Math.min(minY, op.y());
            minZ = Math.min(minZ, op.z());
            maxX = Math.max(maxX, op.x());
            maxY = Math.max(maxY, op.y());
            maxZ = Math.max(maxZ, op.z());
        }
        return new Region(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
