// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.engine.ReadRegionService;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RequestValidator;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.rpc.RpcError;
import cc.wujm.ashlar.rpc.RpcHandler;
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

    private final ConfigHolder configHolder;
    private final ReadRegionService readRegionService;

    public ReadRegionHandler(ConfigHolder configHolder, ReadRegionService readRegionService) {
        this.configHolder = configHolder;
        this.readRegionService = readRegionService;
    }

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(configHolder.get());
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
            Region region = validator.validateReadRegion(params, heights[0], heights[1], configHolder.get().limits().maxReadVolume());
            return readRegionService.read(world, region, ctx);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
