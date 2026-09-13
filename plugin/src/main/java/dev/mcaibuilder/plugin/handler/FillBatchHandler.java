// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.config.PluginConfig;
import dev.mcaibuilder.plugin.engine.FillOp;
import dev.mcaibuilder.plugin.engine.FillTask;
import dev.mcaibuilder.plugin.engine.Region;
import dev.mcaibuilder.plugin.engine.RequestValidator;
import dev.mcaibuilder.plugin.engine.TickBudgetExecutor;
import dev.mcaibuilder.plugin.net.ClientSession;
import dev.mcaibuilder.plugin.rpc.ErrorCode;
import dev.mcaibuilder.plugin.rpc.MainThread;
import dev.mcaibuilder.plugin.rpc.RpcError;
import dev.mcaibuilder.plugin.rpc.RpcHandler;
import org.bukkit.World;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code fill_batch}: batch rectangular-region fills (spec &sect;3.3).
 * Validation (world, build-region, Y range, volume, block parsing) happens
 * entirely before enqueueing, per plan.md &sect;2.2: a request either runs
 * fully or is rejected up front, never half-executed. Only the world's
 * min/max height is a Bukkit {@code World} method and needs a main-thread
 * round trip, fetched once via {@link MainThread#call}; every other check
 * is pure computation.
 *
 * <p>Any {@link RpcError} raised while building the task (either here or by
 * {@link TickBudgetExecutor#submit}, e.g. {@code QUEUE_FULL}) is converted
 * to a failed future so {@code RpcDispatcher}'s single error-unwrapping path
 * handles it, rather than needing a second synchronous-exception path.
 */
public final class FillBatchHandler implements RpcHandler {

    private final PluginConfig config;
    private final TickBudgetExecutor executor;

    public FillBatchHandler(PluginConfig config, TickBudgetExecutor executor) {
        this.config = config;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            JsonArray opsArray = requireNonEmptyArray(params, "ops");

            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                    .thenCompose(heights -> startFill(validator, world, opsArray, params, heights, session, id));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startFill(RequestValidator validator, World world, JsonArray opsArray,
            JsonObject params, int[] heights, ClientSession session, JsonElement id) {
        try {
            List<FillOp> ops = validator.validateFillOps(opsArray, heights[0], heights[1]);
            Region region = boundingRegion(ops);
            boolean connect = validator.resolveConnect(params);
            FillTask task = new FillTask(region, ops, world, connect);
            return executor.submit(task, session, id);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
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

    private static JsonArray requireNonEmptyArray(JsonObject params, String field) {
        if (!params.has(field) || !params.get(field).isJsonArray() || params.getAsJsonArray(field).isEmpty()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be a non-empty array");
        }
        return params.getAsJsonArray(field);
    }
}
