// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.engine.RequestValidator;
import net.rcwalter.ashlar.engine.SparseOp;
import net.rcwalter.ashlar.engine.SparseService;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;
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
            List<SparseOp> ops = validator.validateSparseOps(blocksArray, heights[0], heights[1]);
            boolean connect = validator.resolveConnect(params);
            return sparseService.set(world, ops, connect, ctx);
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
