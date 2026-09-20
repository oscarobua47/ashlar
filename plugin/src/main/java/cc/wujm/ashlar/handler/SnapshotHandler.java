// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.engine.BlockDataParser;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;
import cc.wujm.ashlar.engine.RequestValidator;
import cc.wujm.ashlar.engine.SnapshotService;
import cc.wujm.ashlar.rpc.ErrorCode;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.rpc.RpcError;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.snapshot.Snapshot;
import cc.wujm.ashlar.snapshot.SnapshotStore;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.concurrent.CompletableFuture;

/**
 * Backs the {@code snapshot} / {@code restore} / {@code list_snapshots}
 * methods (spec &sect;3.3, plan.md &sect;3.1: "one class, three {@code
 * RpcHandler} instances"). Each RPC method is exposed as a small {@link
 * RpcHandler}-shaped method reference via {@link #snapshot()}/{@link
 * #restore()}/{@link #listSnapshots()}, registered individually in {@code
 * AshlarPlugin}. Validation stays here; execution (reading/writing the
 * world) lives in {@link SnapshotService}, shared with the in-process tool
 * layer (plan.md step7).
 */
public final class SnapshotHandler {

    private final PluginConfig config;
    private final SnapshotService snapshotService;
    private final SnapshotStore store;

    public SnapshotHandler(PluginConfig config, SnapshotService snapshotService, SnapshotStore store) {
        this.config = config;
        this.snapshotService = snapshotService;
        this.store = store;
    }

    public RpcHandler snapshot() {
        return this::handleSnapshot;
    }

    public RpcHandler restore() {
        return this::handleRestore;
    }

    public RpcHandler listSnapshots() {
        return this::handleListSnapshots;
    }

    // ------------------------------------------------------------------
    // snapshot
    // ------------------------------------------------------------------

    private CompletableFuture<JsonElement> handleSnapshot(InvocationContext ctx, JsonObject params) {
        if (!config.snapshot().enabled()) {
            return CompletableFuture.failedFuture(new RpcError(ErrorCode.DISABLED, "snapshot is disabled in config.yml"));
        }
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            String label = optString(params, "label");
            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                    .thenCompose(heights -> startSnapshot(validator, world, params, heights, label, ctx));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startSnapshot(RequestValidator validator, World world, JsonObject params,
            int[] heights, String label, InvocationContext ctx) {
        try {
            Region region = validator.validateReadRegion(params, heights[0], heights[1], config.snapshot().maxVolume());
            return snapshotService.snapshot(world, region, label, ctx);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ------------------------------------------------------------------
    // restore
    // ------------------------------------------------------------------

    private CompletableFuture<JsonElement> handleRestore(InvocationContext ctx, JsonObject params) {
        try {
            String snapshotId = requireString(params, "id");
            Snapshot snapshot = store.get(snapshotId)
                    .orElseThrow(() -> new RpcError(ErrorCode.BAD_REQUEST, "unknown snapshot id: '" + snapshotId + "'"));
            World world = Bukkit.getWorld(snapshot.world());
            if (world == null) {
                throw new RpcError(ErrorCode.WORLD_NOT_ALLOWED, "world for this snapshot no longer exists: '" + snapshot.world() + "'");
            }
            RequestValidator validator = new RequestValidator(config);
            validator.checkChunkCount(snapshot.region());

            // Parse every palette entry before enqueueing (network thread; Bukkit.createBlockData
            // is a safe off-main-thread registry lookup). One bad entry rejects the whole restore.
            RegionData data = snapshot.data();
            BlockData[] paletteBlocks = new BlockData[data.palette().size()];
            for (int i = 0; i < paletteBlocks.length; i++) {
                paletteBlocks[i] = BlockDataParser.parse(data.palette().get(i));
            }

            return snapshotService.restore(snapshot, world, paletteBlocks, ctx).thenApply(resultJson -> {
                JsonObject o = resultJson.getAsJsonObject();
                o.addProperty("id", snapshot.id());
                return (JsonElement) o;
            });
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ------------------------------------------------------------------
    // list_snapshots
    // ------------------------------------------------------------------

    private CompletableFuture<JsonElement> handleListSnapshots(InvocationContext ctx, JsonObject params) {
        JsonArray arr = new JsonArray();
        for (Snapshot s : store.listNewestFirst()) {
            arr.add(SnapshotService.snapshotResultJson(s));
        }
        JsonObject result = new JsonObject();
        result.add("snapshots", arr);
        return CompletableFuture.completedFuture(result);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String requireString(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive() || !obj.get(field).getAsJsonPrimitive().isString()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be a string");
        }
        return obj.get(field).getAsString();
    }

    private static String optString(JsonObject obj, String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return null;
        }
        JsonElement e = obj.get(field);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be a string");
        }
        return e.getAsString();
    }
}
