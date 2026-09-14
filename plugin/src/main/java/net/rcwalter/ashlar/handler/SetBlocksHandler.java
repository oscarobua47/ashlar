// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.engine.Region;
import net.rcwalter.ashlar.engine.RequestValidator;
import net.rcwalter.ashlar.engine.SparseOp;
import net.rcwalter.ashlar.engine.SparseTask;
import net.rcwalter.ashlar.engine.TickBudgetExecutor;
import net.rcwalter.ashlar.net.ClientSession;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;
import org.bukkit.World;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code set_blocks}: sparse coordinate/block-data pairs for detail work
 * (window frames, decorations, a single oriented stair) (spec &sect;3.3).
 * Same up-front validation strategy as {@link FillBatchHandler}.
 */
public final class SetBlocksHandler implements RpcHandler {

    private final PluginConfig config;
    private final TickBudgetExecutor executor;

    public SetBlocksHandler(PluginConfig config, TickBudgetExecutor executor) {
        this.config = config;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            JsonArray blocksArray = requireNonEmptyArray(params, "blocks");

            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                    .thenCompose(heights -> startSet(validator, world, blocksArray, params, heights, session, id));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startSet(RequestValidator validator, World world, JsonArray blocksArray,
            JsonObject params, int[] heights, ClientSession session, JsonElement id) {
        try {
            List<SparseOp> ops = validator.validateSparseOps(blocksArray, heights[0], heights[1]);
            Region region = boundingRegion(ops);
            boolean connect = validator.resolveConnect(params);
            SparseTask task = new SparseTask(region, ops, world, connect, config.engine().supportWarnings());
            return executor.submit(task, session, id);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
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

    private static JsonArray requireNonEmptyArray(JsonObject params, String field) {
        if (!params.has(field) || !params.get(field).isJsonArray() || params.getAsJsonArray(field).isEmpty()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be a non-empty array");
        }
        return params.getAsJsonArray(field);
    }
}
