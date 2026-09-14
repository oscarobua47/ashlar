// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.engine.HeightmapService;
import net.rcwalter.ashlar.engine.HeightmapTypes;
import net.rcwalter.ashlar.engine.RequestValidator;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;
import org.bukkit.HeightMap;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * {@code heightmap}: reads surface height over an x/z area using Paper's
 * built-in heightmap API (spec &sect;3.3). Maps the spec's type names onto
 * {@link HeightMap} per plan.md &sect;3.2; also accepts the Bukkit enum names
 * directly, but never the deprecated/removed {@code com.destroystokyo.paper
 * .HeightmapType} and never the internal {@code *_WG} variants (worldgen
 * heightmaps, not meant for post-generation queries). Execution lives in
 * {@link HeightmapService}, shared with the in-process tool layer (plan.md
 * step7).
 *
 * <p>Unlike {@code fill_batch}/{@code set_blocks}, no world-min/max-height
 * round trip is needed here: build-region and area/chunk checks are pure
 * config comparisons, safe on the network thread.
 */
public final class HeightmapHandler implements RpcHandler {

    private final PluginConfig config;
    private final HeightmapService heightmapService;

    public HeightmapHandler(PluginConfig config, HeightmapService heightmapService) {
        this.config = config;
        this.heightmapService = heightmapService;
    }

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            String typeName = optType(params);
            HeightMap heightMap = HeightmapTypes.resolve(typeName);
            RequestValidator.HeightmapArea area = validator.validateHeightmapArea(params, config.limits().maxReadVolume());
            return heightmapService.heightmap(world, area.x1(), area.z1(), area.x2(), area.z2(), heightMap, typeName, ctx);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String optType(JsonObject params) {
        if (!params.has("type") || params.get("type").isJsonNull()) {
            return HeightmapTypes.DEFAULT_TYPE;
        }
        JsonElement e = params.get("type");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"type\" must be a string");
        }
        return e.getAsString();
    }
}
