// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.engine.ReadRegionService;
import net.rcwalter.ashlar.engine.Region;
import net.rcwalter.ashlar.engine.RequestValidator;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * {@code read_region}: reads a region's full block data, palette + run-length
 * encoded (spec &sect;3.4). Capped by {@code limits.max-read-volume}, unlike
 * {@code fill_batch}/{@code set_blocks} which use {@code
 * limits.max-blocks-per-operation}. Execution lives in {@link
 * ReadRegionService}, shared with the in-process tool layer (plan.md step7).
 */
public final class ReadRegionHandler implements RpcHandler {

    private final PluginConfig config;
    private final ReadRegionService readRegionService;

    public ReadRegionHandler(PluginConfig config, ReadRegionService readRegionService) {
        this.config = config;
        this.readRegionService = readRegionService;
    }

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                    .thenCompose(heights -> startRead(validator, world, params, heights, ctx));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startRead(RequestValidator validator, World world, JsonObject params,
            int[] heights, InvocationContext ctx) {
        try {
            Region region = validator.validateReadRegion(params, heights[0], heights[1], config.limits().maxReadVolume());
            return readRegionService.read(world, region, ctx);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
