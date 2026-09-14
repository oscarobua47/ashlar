// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.engine.HeightmapTypes;
import net.rcwalter.ashlar.engine.Region;
import net.rcwalter.ashlar.engine.RenderService;
import net.rcwalter.ashlar.engine.RequestValidator;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;
import org.bukkit.HeightMap;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * {@code render}: projects a region into a PNG (top/side/slice views,
 * docs/prompts/step4e-prompt.md). Parses and validates params for each of
 * the three view families (region, top, heightmap); execution (the budgeted
 * read plus image work) lives in {@link RenderService}, shared with the
 * in-process tool layer (plan.md step7).
 */
public final class RenderHandler implements RpcHandler {

    private final PluginConfig config;
    private final RenderService renderService;

    public RenderHandler(PluginConfig config, RenderService renderService) {
        this.config = config;
        this.renderService = renderService;
    }

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            String view = validator.peekRenderView(params);
            if ("heightmap".equals(view)) {
                return startHeightmapRender(validator, world, params, ctx);
            }
            if ("top".equals(view)) {
                return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                        .thenCompose(heights -> startTopRender(validator, world, params, heights, ctx));
            }
            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                    .thenCompose(heights -> startRender(validator, world, params, heights, ctx));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startRender(RequestValidator validator, World world, JsonObject params,
            int[] heights, InvocationContext ctx) {
        try {
            Region region = validator.validateReadRegion(params, heights[0], heights[1], config.limits().maxReadVolume());
            RequestValidator.RenderParams renderParams = validator.validateRenderParams(params, region);
            return renderService.renderRegion(world, region, renderParams, ctx);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startTopRender(RequestValidator validator, World world, JsonObject params,
            int[] worldHeights, InvocationContext ctx) {
        try {
            Region region = validator.validateTopRegion(params, worldHeights[0], worldHeights[1], config.limits().maxReadVolume());
            RequestValidator.RenderParams renderParams = validator.validateRenderParams(params, region);
            return renderService.renderTop(world, region, renderParams, ctx);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startHeightmapRender(RequestValidator validator, World world,
            JsonObject params, InvocationContext ctx) {
        try {
            RequestValidator.HeightmapRenderParams hp =
                    validator.validateHeightmapRenderParams(params, config.limits().maxReadVolume());
            HeightMap requestedMap = HeightmapTypes.resolve(hp.type());
            return renderService.renderHeightmap(world, requestedMap, hp, ctx);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
