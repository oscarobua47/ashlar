// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.config.PluginConfig;
import dev.mcaibuilder.plugin.rpc.ErrorCode;
import dev.mcaibuilder.plugin.rpc.RpcError;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates {@code fill_batch}/{@code set_blocks} requests entirely before
 * anything is enqueued (plan.md &sect;2.1: validation runs entirely on the
 * network thread; any failure rejects the whole request, nothing runs
 * half-way). Checks run in the fixed order documented in
 * plan.md &sect;2.1/&sect;2.2 so, when a request violates more than one rule,
 * the first violated rule in this order always determines the returned error
 * code: world allow-list &rarr; build-region &rarr; Y range &rarr; per-op
 * volume &rarr; total volume &rarr; block string parsing.
 *
 * <p>{@link World#getMinHeight()}/{@link World#getMaxHeight()} are Bukkit
 * {@code World} instance methods and must be read on the main thread;
 * callers take a one-time snapshot via {@code MainThread.call} and pass the
 * two ints in here rather than letting the validator touch the World object
 * for anything but {@link Bukkit#getWorld(String)} (a plain registry lookup,
 * safe off the main thread).
 */
public final class RequestValidator {

    private final PluginConfig config;

    public RequestValidator(PluginConfig config) {
        this.config = config;
    }

    /** Resolves and allow-list-checks the target world. Safe to call off the main thread. */
    public World resolveWorld(JsonObject params) {
        String worldName = optString(params, "world", config.world().defaultWorld());
        if (!config.world().allowedWorlds().contains(worldName)) {
            throw new RpcError(ErrorCode.WORLD_NOT_ALLOWED, "world not allowed: '" + worldName + "'");
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            throw new RpcError(ErrorCode.WORLD_NOT_ALLOWED, "world does not exist: '" + worldName + "'");
        }
        return world;
    }

    /** Validates a {@code fill_batch} request's {@code ops} array and parses it into {@link FillOp}s. */
    public List<FillOp> validateFillOps(JsonArray opsArray, int worldMinHeight, int worldMaxHeight) {
        List<Region> regions = new ArrayList<>(opsArray.size());
        long totalVolume = 0;
        for (JsonElement el : opsArray) {
            JsonObject op = requireObject(el, "op");
            Region region = Region.of(requireCoords(op, "from"), requireCoords(op, "to"));
            checkBuildRegion(region.minX(), region.maxX(), region.minZ(), region.maxZ());
            checkYRange(region.minY(), region.maxY(), worldMinHeight, worldMaxHeight);
            long volume = region.volume();
            checkVolume(volume, "op volume");
            checkChunkCount(region);
            totalVolume += volume;
            regions.add(region);
        }
        checkVolume(totalVolume, "total volume");

        List<FillOp> result = new ArrayList<>(opsArray.size());
        for (int i = 0; i < opsArray.size(); i++) {
            JsonObject op = opsArray.get(i).getAsJsonObject();
            BlockData block = BlockDataParser.parse(requireString(op, "block"));
            FillMode mode = FillMode.fromString(optString(op, "mode", null));
            String filterStr = optString(op, "filter", null);
            BlockData filter = filterStr == null ? null : BlockDataParser.parse(filterStr);
            result.add(new FillOp(regions.get(i), block, mode, filter));
        }
        return result;
    }

    /** Validates a {@code set_blocks} request's {@code blocks} array and parses it into {@link SparseOp}s. */
    public List<SparseOp> validateSparseOps(JsonArray blocksArray, int worldMinHeight, int worldMaxHeight) {
        // Sparse requests have no sub-operations; the whole array counts once against the limit.
        checkVolume(blocksArray.size(), "block count");

        List<int[]> positions = new ArrayList<>(blocksArray.size());
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (JsonElement el : blocksArray) {
            JsonObject entry = requireObject(el, "block entry");
            int[] pos = requireCoords(entry, "pos");
            checkBuildRegion(pos[0], pos[0], pos[2], pos[2]);
            checkYRange(pos[1], pos[1], worldMinHeight, worldMaxHeight);
            positions.add(pos);
            minX = Math.min(minX, pos[0]);
            minY = Math.min(minY, pos[1]);
            minZ = Math.min(minZ, pos[2]);
            maxX = Math.max(maxX, pos[0]);
            maxY = Math.max(maxY, pos[1]);
            maxZ = Math.max(maxZ, pos[2]);
        }
        checkChunkCount(new Region(minX, minY, minZ, maxX, maxY, maxZ));

        List<SparseOp> result = new ArrayList<>(blocksArray.size());
        for (int i = 0; i < blocksArray.size(); i++) {
            JsonObject entry = blocksArray.get(i).getAsJsonObject();
            BlockData block = BlockDataParser.parse(requireString(entry, "block"));
            int[] pos = positions.get(i);
            result.add(new SparseOp(pos[0], pos[1], pos[2], block));
        }
        return result;
    }

    /**
     * Validates a {@code read_region}/{@code snapshot}-style request: a
     * single inclusive {@code from}/{@code to} region checked against
     * build-region, world Y range, a caller-supplied volume limit ({@code
     * limits.max-read-volume} for read_region, {@code snapshot.max-volume}
     * for snapshot) and {@code limits.max-chunks-per-operation}.
     */
    public Region validateReadRegion(JsonObject params, int worldMinHeight, int worldMaxHeight, long maxVolume) {
        Region region = Region.of(requireCoords(params, "from"), requireCoords(params, "to"));
        checkBuildRegion(region.minX(), region.maxX(), region.minZ(), region.maxZ());
        checkYRange(region.minY(), region.maxY(), worldMinHeight, worldMaxHeight);
        checkVolumeLimit(region.volume(), maxVolume, "region volume");
        checkChunkCount(region);
        return region;
    }

    /** The x/z area (inclusive) a validated {@code heightmap} request covers. */
    public record HeightmapArea(int x1, int z1, int x2, int z2) {
    }

    /**
     * Validates a {@code heightmap} request's {@code from}/{@code to} (each
     * an {@code [x,z]} pair, spec &sect;3.2) against build-region, the area
     * limit ({@code limits.max-read-volume}, reused per plan.md &sect;3.2) and
     * {@code limits.max-chunks-per-operation} (treating the area as a region
     * with a single y, plan.md &sect;2.6/&sect;3.1).
     */
    public HeightmapArea validateHeightmapArea(JsonObject params, long maxArea) {
        int[] from = requireCoords2(params, "from");
        int[] to = requireCoords2(params, "to");
        int x1 = Math.min(from[0], to[0]);
        int x2 = Math.max(from[0], to[0]);
        int z1 = Math.min(from[1], to[1]);
        int z2 = Math.max(from[1], to[1]);
        checkBuildRegion(x1, x2, z1, z2);
        long area = (long) (x2 - x1 + 1) * (z2 - z1 + 1);
        checkVolumeLimit(area, maxArea, "heightmap area");
        checkChunkCount(new Region(x1, 0, z1, x2, 0, z2));
        return new HeightmapArea(x1, z1, x2, z2);
    }

    private void checkBuildRegion(int minX, int maxX, int minZ, int maxZ) {
        PluginConfig.WorldConfig.BuildRegion buildRegion = config.world().buildRegion();
        if (!buildRegion.enabled()) {
            return;
        }
        if (minX < buildRegion.minX() || maxX > buildRegion.maxX()
                || minZ < buildRegion.minZ() || maxZ > buildRegion.maxZ()) {
            throw new RpcError(ErrorCode.OUT_OF_BUILD_REGION,
                    "operation at x=[" + minX + "," + maxX + "] z=[" + minZ + "," + maxZ
                            + "] is outside the configured build region x=[" + buildRegion.minX() + "," + buildRegion.maxX()
                            + "] z=[" + buildRegion.minZ() + "," + buildRegion.maxZ() + "]");
        }
    }

    private void checkYRange(int minY, int maxY, int worldMinHeight, int worldMaxHeight) {
        int highestValidY = worldMaxHeight - 1; // World#getMaxHeight() is an exclusive upper bound.
        if (minY < worldMinHeight || maxY > highestValidY) {
            throw new RpcError(ErrorCode.BAD_REQUEST,
                    "y range [" + minY + "," + maxY + "] is outside the world's valid range ["
                            + worldMinHeight + "," + highestValidY + "]");
        }
    }

    private void checkVolume(long volume, String what) {
        checkVolumeLimit(volume, config.limits().maxBlocksPerOperation(), what);
    }

    /** Shared volume-vs-limit check; callers pick the limit (different RPCs cap against different config values). */
    public void checkVolumeLimit(long volume, long max, String what) {
        if (volume > max) {
            throw new RpcError(ErrorCode.VOLUME_EXCEEDED, what + " " + volume + " exceeds limit " + max);
        }
    }

    /**
     * Checks a region's x/z footprint against {@code limits.max-chunks-per-operation}
     * (plan.md &sect;2.6/&sect;3.1). Applies to every operation that force-loads
     * chunks: fill_batch (per op), set_blocks (bounding box of the sparse
     * points), read_region, snapshot, restore (the snapshot's region), and
     * heightmap (x/z area treated as a region with a single y).
     */
    public void checkChunkCount(Region region) {
        long chunks = region.chunkCount();
        long max = config.limits().maxChunksPerOperation();
        if (chunks > max) {
            throw new RpcError(ErrorCode.VOLUME_EXCEEDED,
                    "operation spans " + chunks + " chunks, exceeding limit " + max);
        }
    }

    private static JsonObject requireObject(JsonElement el, String what) {
        if (el == null || !el.isJsonObject()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "each " + what + " must be a JSON object");
        }
        return el.getAsJsonObject();
    }

    private static int[] requireCoords(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonArray()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be an array of 3 integers");
        }
        JsonArray arr = obj.getAsJsonArray(field);
        if (arr.size() != 3) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must have exactly 3 integers, got " + arr.size());
        }
        int[] result = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must contain only integers");
            }
            result[i] = e.getAsInt();
        }
        return result;
    }

    private static int[] requireCoords2(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonArray()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be an array of 2 integers");
        }
        JsonArray arr = obj.getAsJsonArray(field);
        if (arr.size() != 2) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must have exactly 2 integers, got " + arr.size());
        }
        int[] result = new int[2];
        for (int i = 0; i < 2; i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must contain only integers");
            }
            result[i] = e.getAsInt();
        }
        return result;
    }

    private static String requireString(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive() || !obj.get(field).getAsJsonPrimitive().isString()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be a string");
        }
        return obj.get(field).getAsString();
    }

    private static String optString(JsonObject obj, String field, String fallback) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return fallback;
        }
        JsonElement e = obj.get(field);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be a string");
        }
        return e.getAsString();
    }
}
