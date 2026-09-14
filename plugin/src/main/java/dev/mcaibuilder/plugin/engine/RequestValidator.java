// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.config.PluginConfig;
import dev.mcaibuilder.plugin.rpc.ErrorCode;
import dev.mcaibuilder.plugin.rpc.RpcError;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
            SignData sign = parseSign(entry, block);
            result.add(new SparseOp(pos[0], pos[1], pos[2], block, sign));
        }
        return result;
    }

    /**
     * Parses an entry's optional {@code "sign"} object (Fix 3,
     * docs/prompts/step4d-prompt.md): {@code {"front":[...], "back":[...],
     * "color":"black", "glowing":false, "waxed":true}}, all fields optional
     * except at least one of {@code front}/{@code back}. Returns {@code
     * null} when the entry has no {@code sign} field. Rejects {@code sign}
     * outright when {@code block}'s material is not a sign, so a shape or
     * material error here always rejects the whole {@code set_blocks}
     * request up front, same as every other check in this class.
     */
    private SignData parseSign(JsonObject entry, BlockData block) {
        if (!entry.has("sign") || entry.get("sign").isJsonNull()) {
            return null;
        }
        if (!entry.get("sign").isJsonObject()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"sign\" must be a JSON object");
        }
        JsonObject signObj = entry.getAsJsonObject("sign");
        String materialName = block.getMaterial().name().toLowerCase(Locale.ROOT);
        if (!materialName.endsWith("_sign") && !materialName.endsWith("_hanging_sign")) {
            throw new RpcError(ErrorCode.BAD_REQUEST,
                    "\"sign\" was given for block '" + materialName
                            + "', which is not a sign (its material name must end with '_sign' or '_hanging_sign')");
        }
        List<String> front = optSignLines(signObj, "front");
        List<String> back = optSignLines(signObj, "back");
        if (front == null && back == null) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"sign\" must include at least one of \"front\"/\"back\"");
        }
        String color = optString(signObj, "color", null);
        if (color != null) {
            try {
                DyeColor.valueOf(color.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new RpcError(ErrorCode.BAD_REQUEST, "\"sign.color\" is not a valid dye color: '" + color + "'");
            }
        }
        boolean glowing = optBoolean(signObj, "glowing", false);
        boolean waxed = optBoolean(signObj, "waxed", false);
        return new SignData(front, back, color, glowing, waxed);
    }

    /** A sign side's lines: 1-4 strings, each &lt;= 64 characters, padded to 4 entries with {@code ""}. */
    private static List<String> optSignLines(JsonObject signObj, String field) {
        if (!signObj.has(field) || signObj.get(field).isJsonNull()) {
            return null;
        }
        if (!signObj.get(field).isJsonArray()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"sign." + field + "\" must be an array of 1-4 strings");
        }
        JsonArray arr = signObj.getAsJsonArray(field);
        if (arr.isEmpty() || arr.size() > 4) {
            throw new RpcError(ErrorCode.BAD_REQUEST,
                    "\"sign." + field + "\" must have between 1 and 4 strings, got " + arr.size());
        }
        List<String> lines = new ArrayList<>(List.of("", "", "", ""));
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                throw new RpcError(ErrorCode.BAD_REQUEST, "\"sign." + field + "\" must contain only strings");
            }
            String line = el.getAsString();
            if (line.length() > 64) {
                throw new RpcError(ErrorCode.BAD_REQUEST, "\"sign." + field + "[" + i + "]\" exceeds 64 characters");
            }
            lines.set(i, line);
        }
        return lines;
    }

    /**
     * Resolves the {@code connect} request-level override for {@code
     * fill_batch}/{@code set_blocks} (Fix 2, docs/prompts/step4d-prompt.md),
     * defaulting to {@code engine.connect-blocks} when the request omits it.
     */
    public boolean resolveConnect(JsonObject params) {
        if (!params.has("connect") || params.get("connect").isJsonNull()) {
            return config.engine().connectBlocks();
        }
        return optBoolean(params, "connect", config.engine().connectBlocks());
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

    /**
     * Validates a {@code render} {@code view: "top"} request (docs/prompts/step4g-prompt.md, Bug 2): the same
     * 3D {@code from}/{@code to} shape as every other render view, but priced by x/z area only, not volume - a
     * top view reads at most one block per column, so its cost does not depend on how tall the y range is. Y
     * range is still validated against world bounds ({@link TopViewTask} uses it as the column-search window)
     * but never against {@code limits.max-read-volume}.
     */
    public Region validateTopRegion(JsonObject params, int worldMinHeight, int worldMaxHeight, long maxArea) {
        // A top view only needs an x/z footprint: [x,z] corners mean "the whole
        // world height", so the surface is found automatically. A 3-element
        // [x,y,z] form clamps the search to that y range (roofs, caves).
        Region region = Region.of(
                topCorner(params, "from", worldMinHeight),
                topCorner(params, "to", worldMaxHeight - 1));
        checkBuildRegion(region.minX(), region.maxX(), region.minZ(), region.maxZ());
        checkYRange(region.minY(), region.maxY(), worldMinHeight, worldMaxHeight);
        long area = (long) (region.maxX() - region.minX() + 1) * (region.maxZ() - region.minZ() + 1);
        checkVolumeLimit(area, maxArea, "top view area");
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

    private static final List<String> VALID_RENDER_VIEWS =
            List.of("top", "north", "south", "east", "west", "slice", "heightmap");
    private static final List<String> VALID_SLICE_AXES = List.of("x", "y", "z");

    /**
     * Reads {@code render}'s {@code "view"} field without validating it against {@link #VALID_RENDER_VIEWS}
     * (docs/prompts/step4f-prompt.md): {@code RenderHandler} needs to know up front whether a request is the
     * {@code "heightmap"} view - which has an entirely different 2D {@code from}/{@code to} shape and skips the
     * normal 3D {@link #validateReadRegion} - before it can validate anything else.
     */
    public String peekRenderView(JsonObject params) {
        return optString(params, "view", "top").trim().toLowerCase(Locale.ROOT);
    }

    /** Validated {@code render} request parameters (docs/prompts/step4e-prompt.md), besides the region (see {@link #validateReadRegion}). */
    public record RenderParams(String view, String sliceAxis, int sliceAt, int scale, int grid) {
    }

    /**
     * Validates a {@code render} request's {@code view}/{@code slice}/{@code scale}/{@code grid} fields. The
     * region itself is validated separately via {@link #validateReadRegion} (same rules as {@code read_region}:
     * world allow-list, build-region, Y range, {@code limits.max-read-volume}, chunk cap), and is passed in here
     * only so {@code slice.at} can be checked against it.
     */
    public RenderParams validateRenderParams(JsonObject params, Region region) {
        String view = optString(params, "view", "top").trim().toLowerCase(Locale.ROOT);
        if (!VALID_RENDER_VIEWS.contains(view)) {
            throw new RpcError(ErrorCode.BAD_REQUEST,
                    "\"view\" must be one of " + VALID_RENDER_VIEWS + ", got '" + view + "'");
        }

        String sliceAxis = null;
        int sliceAt = 0;
        if (view.equals("slice")) {
            if (!params.has("slice") || !params.get("slice").isJsonObject()) {
                throw new RpcError(ErrorCode.BAD_REQUEST,
                        "view \"slice\" requires a \"slice\" object with \"axis\" and \"at\"");
            }
            JsonObject sliceObj = params.getAsJsonObject("slice");
            sliceAxis = requireString(sliceObj, "axis").trim().toLowerCase(Locale.ROOT);
            if (!VALID_SLICE_AXES.contains(sliceAxis)) {
                throw new RpcError(ErrorCode.BAD_REQUEST,
                        "\"slice.axis\" must be one of " + VALID_SLICE_AXES + ", got '" + sliceAxis + "'");
            }
            sliceAt = requireInt(sliceObj, "at");
            int lo, hi;
            switch (sliceAxis) {
                case "x" -> {
                    lo = region.minX();
                    hi = region.maxX();
                }
                case "y" -> {
                    lo = region.minY();
                    hi = region.maxY();
                }
                default -> {
                    lo = region.minZ();
                    hi = region.maxZ();
                }
            }
            if (sliceAt < lo || sliceAt > hi) {
                throw new RpcError(ErrorCode.BAD_REQUEST,
                        "\"slice.at\" (" + sliceAt + ") must lie within [" + lo + "," + hi
                                + "] on axis '" + sliceAxis + "'");
            }
        }

        return new RenderParams(view, sliceAxis, sliceAt, validateScale(params), validateGrid(params));
    }

    /** The x/z area (inclusive) plus the image knobs a validated {@code render} {@code view: "heightmap"} request covers. */
    public record HeightmapRenderParams(int x1, int z1, int x2, int z2, String type, int scale, int grid, int contour) {
    }

    /**
     * Validates a {@code render} request's {@code view: "heightmap"} fields (docs/prompts/step4f-prompt.md): the
     * area is 2D {@code from}/{@code to} (same rule as {@link #validateHeightmapArea}, reusing {@code
     * limits.max-read-volume} as the area cap), plus {@code type} (defaulted, not resolved to a Bukkit {@code
     * HeightMap} here - unknown names are rejected by {@code HeightmapTypes.resolve} in the handler, same as the
     * {@code heightmap} RPC), {@code scale}/{@code grid} (same rules as {@link #validateRenderParams}) and {@code
     * contour} (blocks per contour line, {@code 0} disables, default 5).
     */
    public HeightmapRenderParams validateHeightmapRenderParams(JsonObject params, long maxArea) {
        HeightmapArea area = validateHeightmapArea(params, maxArea);
        String type = optString(params, "type", HeightmapTypes.DEFAULT_TYPE);
        int contour = optInt(params, "contour", 5);
        if (contour < 0) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"contour\" must be >= 0, got " + contour);
        }
        return new HeightmapRenderParams(area.x1(), area.z1(), area.x2(), area.z2(), type,
                validateScale(params), validateGrid(params), contour);
    }

    private int validateScale(JsonObject params) {
        int scale = optInt(params, "scale", 0);
        if (scale < 0 || scale > 16) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"scale\" must be between 0 and 16, got " + scale);
        }
        return scale;
    }

    private int validateGrid(JsonObject params) {
        int grid = optInt(params, "grid", 10);
        if (grid < 0 || grid > 64) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"grid\" must be between 0 and 64, got " + grid);
        }
        return grid;
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

    /** Accepts [x,z] (y filled with {@code defaultY}) or [x,y,z] for views that only need a footprint. */
    private static int[] topCorner(JsonObject obj, String field, int defaultY) {
        if (obj.has(field) && obj.get(field).isJsonArray() && obj.getAsJsonArray(field).size() == 2) {
            int[] xz = requireCoords2(obj, field);
            return new int[] {xz[0], defaultY, xz[1]};
        }
        return requireCoords(obj, field);
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

    private static int requireInt(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive() || !obj.get(field).getAsJsonPrimitive().isNumber()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be an integer");
        }
        return obj.get(field).getAsInt();
    }

    private static int optInt(JsonObject obj, String field, int fallback) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return fallback;
        }
        JsonElement e = obj.get(field);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be a number");
        }
        return e.getAsInt();
    }

    private static boolean optBoolean(JsonObject obj, String field, boolean fallback) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return fallback;
        }
        JsonElement e = obj.get(field);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be a boolean");
        }
        return e.getAsBoolean();
    }
}
