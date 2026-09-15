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
 * The execution path behind {@code fill_batch} (spec &sect;3.3), split out
 * of {@code FillBatchHandler} (plan.md step7) so the same path can be
 * reused by the in-process tool layer/agent, not just the WebSocket RPC
 * handler. Callers must have already validated {@code ops} (world,
 * build-region, Y range, volume, block parsing) - this class only builds
 * the bounding region, constructs the task and enqueues it.
 */
public final class FillService {

    private final PluginConfig config;
    private final TickBudgetExecutor executor;

    public FillService(PluginConfig config, TickBudgetExecutor executor) {
        this.config = config;
        this.executor = executor;
    }

    /**
     * Enqueues a batch fill of {@code ops} on {@code world}. Must not be called from the main
     * thread. {@code liquidsFlow} is the request's {@code "liquids": "flow"} (step8d-prompt.md):
     * when true, every written block whose material is a liquid ({@link LiquidBlocks#isFlowable})
     * is written with physics enabled so it spreads like a hand-placed fluid; every other block
     * (and every liquid when {@code liquidsFlow} is false) keeps the usual physics-free write.
     */
    public CompletableFuture<JsonElement> fill(World world, List<FillOp> ops, boolean connect, boolean liquidsFlow,
            InvocationContext ctx) {
        MainThread.assertNotPrimary("FillService.fill");
        Region region = boundingRegion(ops);
        FillTask task = new FillTask(region, ops, world, connect, config.engine().supportWarnings(), liquidsFlow);
        return executor.submit(task, ctx);
    }

    private static Region boundingRegion(List<FillOp> ops) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (FillOp op : ops) {
            Region r = op.region();
            minX = Math.min(minX, r.minX());
            minY = Math.min(minY, r.minY());
            minZ = Math.min(minZ, r.minZ());
            maxX = Math.max(maxX, r.maxX());
            maxY = Math.max(maxY, r.maxY());
            maxZ = Math.max(maxZ, r.maxZ());
        }
        return new Region(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
