// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.config.PluginConfig;
import dev.mcaibuilder.plugin.engine.HeightmapTask;
import dev.mcaibuilder.plugin.engine.Region;
import dev.mcaibuilder.plugin.engine.RequestValidator;
import dev.mcaibuilder.plugin.engine.TickBudgetExecutor;
import dev.mcaibuilder.plugin.net.ClientSession;
import dev.mcaibuilder.plugin.rpc.ErrorCode;
import dev.mcaibuilder.plugin.rpc.RpcError;
import dev.mcaibuilder.plugin.rpc.RpcHandler;
import org.bukkit.HeightMap;
import org.bukkit.World;

import java.util.Locale;
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
            HeightMap heightMap = resolveHeightMap(typeName);
            RequestValidator.HeightmapArea area = validator.validateHeightmapArea(params, config.limits().maxReadVolume());
            Region region = new Region(area.x1(), 0, area.z1(), area.x2(), 0, area.z2());
            HeightmapTask task = new HeightmapTask(region, world, area.x1(), area.z1(), area.x2(), area.z2(), heightMap, typeName);
            return executor.submit(task, session, id);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Spec &sect;3.2 name &rarr; Bukkit {@link HeightMap}; also accepts the Bukkit names directly. Unknown/{@code *_WG} &rarr; BAD_REQUEST. */
    private static HeightMap resolveHeightMap(String raw) {
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "SOLID", "OCEAN_FLOOR" -> HeightMap.OCEAN_FLOOR;
            case "SOLID_OR_LIQUID", "MOTION_BLOCKING" -> HeightMap.MOTION_BLOCKING;
            case "SOLID_OR_LIQUID_NO_LEAVES", "MOTION_BLOCKING_NO_LEAVES" -> HeightMap.MOTION_BLOCKING_NO_LEAVES;
            case "ANY", "WORLD_SURFACE" -> HeightMap.WORLD_SURFACE;
            default -> throw new RpcError(ErrorCode.BAD_REQUEST, "unknown heightmap type: '" + raw + "'");
        };
    }

    private static String optType(JsonObject params) {
        if (!params.has("type") || params.get("type").isJsonNull()) {
            return "SOLID_OR_LIQUID_NO_LEAVES"; // spec default
        }
        JsonElement e = params.get("type");
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"type\" must be a string");
        }
        return e.getAsString();
    }
}
