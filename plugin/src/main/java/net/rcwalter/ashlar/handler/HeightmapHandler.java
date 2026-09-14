// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.engine.HeightmapTask;
import net.rcwalter.ashlar.engine.HeightmapTypes;
import net.rcwalter.ashlar.engine.Region;
import net.rcwalter.ashlar.engine.RequestValidator;
import net.rcwalter.ashlar.engine.TickBudgetExecutor;
import net.rcwalter.ashlar.net.ClientSession;
import net.rcwalter.ashlar.rpc.ErrorCode;
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
 * heightmaps, not meant for post-generation queries).
 *
 * <p>Unlike {@code fill_batch}/{@code set_blocks}, no world-min/max-height
 * round trip is needed here: build-region and area/chunk checks are pure
 * config comparisons, safe on the network thread.
 */
public final class HeightmapHandler implements RpcHandler {

    private final PluginConfig config;
    private final TickBudgetExecutor executor;

    public HeightmapHandler(PluginConfig config, TickBudgetExecutor executor) {
        this.config = config;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            String typeName = optType(params);
            HeightMap heightMap = HeightmapTypes.resolve(typeName);
            RequestValidator.HeightmapArea area = validator.validateHeightmapArea(params, config.limits().maxReadVolume());
            Region region = new Region(area.x1(), 0, area.z1(), area.x2(), 0, area.z2());
            HeightmapTask task = new HeightmapTask(region, world, area.x1(), area.z1(), area.x2(), area.z2(), heightMap, typeName);
            return executor.submit(task, session, id);
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
