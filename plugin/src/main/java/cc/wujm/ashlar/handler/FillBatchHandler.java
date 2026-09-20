// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.engine.FillOp;
import cc.wujm.ashlar.engine.FillService;
import cc.wujm.ashlar.rpc.ErrorCode;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.rpc.RpcError;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.engine.RequestValidator;
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
 * is pure computation. Execution itself (building the task and enqueueing
 * it) lives in {@link FillService}, shared with the in-process tool layer
 * (plan.md step7).
 *
 * <p>Any {@link RpcError} raised while building the task (either here or by
 * {@link cc.wujm.ashlar.engine.TickBudgetExecutor#submit}, e.g.
 * {@code QUEUE_FULL}) is converted to a failed future so {@code
 * RpcDispatcher}'s single error-unwrapping path handles it, rather than
 * needing a second synchronous-exception path.
 */
public final class FillBatchHandler implements RpcHandler {

    private final PluginConfig config;
    private final FillService fillService;

    public FillBatchHandler(PluginConfig config, FillService fillService) {
        this.config = config;
        this.fillService = fillService;
    }

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            JsonArray opsArray = requireNonEmptyArray(params, "ops");

            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                    .thenCompose(heights -> startFill(validator, world, opsArray, params, heights, ctx));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startFill(RequestValidator validator, World world, JsonArray opsArray,
            JsonObject params, int[] heights, InvocationContext ctx) {
        try {
            boolean liquidsFlow = validator.resolveLiquidsFlow(params);
            List<FillOp> ops = validator.validateFillOps(opsArray, heights[0], heights[1], liquidsFlow);
            boolean connect = validator.resolveConnect(params);
            return fillService.fill(world, ops, connect, liquidsFlow, ctx);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static JsonArray requireNonEmptyArray(JsonObject params, String field) {
        if (!params.has(field) || !params.get(field).isJsonArray() || params.getAsJsonArray(field).isEmpty()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be a non-empty array");
        }
        return params.getAsJsonArray(field);
    }
}
