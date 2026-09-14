// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.engine.ReadTask;
import net.rcwalter.ashlar.engine.Region;
import net.rcwalter.ashlar.engine.RequestValidator;
import net.rcwalter.ashlar.engine.TickBudgetExecutor;
import net.rcwalter.ashlar.net.ClientSession;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * {@code read_region}: reads a region's full block data, palette + run-length
 * encoded (spec &sect;3.4). Capped by {@code limits.max-read-volume}, unlike
 * {@code fill_batch}/{@code set_blocks} which use {@code
 * limits.max-blocks-per-operation}.
 */
public final class ReadRegionHandler implements RpcHandler {

    private final PluginConfig config;
    private final TickBudgetExecutor executor;

    public ReadRegionHandler(PluginConfig config, TickBudgetExecutor executor) {
        this.config = config;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                    .thenCompose(heights -> startRead(validator, world, params, heights, session, id));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startRead(RequestValidator validator, World world, JsonObject params,
            int[] heights, ClientSession session, JsonElement id) {
        try {
            Region region = validator.validateReadRegion(params, heights[0], heights[1], config.limits().maxReadVolume());
            ReadTask task = new ReadTask(region, world);
            return executor.submit(task, session, id);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
