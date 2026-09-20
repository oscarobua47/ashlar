// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.engine.RequestValidator;
import cc.wujm.ashlar.engine.SparseOp;
import cc.wujm.ashlar.engine.SparseService;
import cc.wujm.ashlar.rpc.ErrorCode;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.rpc.RpcError;
import cc.wujm.ashlar.rpc.RpcHandler;
import org.bukkit.World;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code set_blocks}: sparse coordinate/block-data pairs for detail work
 * (window frames, decorations, a single oriented stair) (spec &sect;3.3).
 * Same up-front validation strategy as {@link FillBatchHandler}. Execution
 * lives in {@link SparseService}, shared with the in-process tool layer
 * (plan.md step7).
 */
public final class SetBlocksHandler implements RpcHandler {

    private final PluginConfig config;
    private final SparseService sparseService;

    public SetBlocksHandler(PluginConfig config, SparseService sparseService) {
        this.config = config;
        this.sparseService = sparseService;
    }

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            JsonArray blocksArray = requireNonEmptyArray(params, "blocks");

            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                    .thenCompose(heights -> startSet(validator, world, blocksArray, params, heights, ctx));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startSet(RequestValidator validator, World world, JsonArray blocksArray,
            JsonObject params, int[] heights, InvocationContext ctx) {
        try {
            boolean liquidsFlow = validator.resolveLiquidsFlow(params);
            List<SparseOp> ops = validator.validateSparseOps(blocksArray, heights[0], heights[1], liquidsFlow);
            boolean connect = validator.resolveConnect(params);
            return sparseService.set(world, ops, connect, liquidsFlow, ctx);
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
