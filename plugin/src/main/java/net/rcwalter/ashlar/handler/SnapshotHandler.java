// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.engine.BlockDataParser;
import net.rcwalter.ashlar.engine.ReadTask;
import net.rcwalter.ashlar.engine.Region;
import net.rcwalter.ashlar.engine.RegionData;
import net.rcwalter.ashlar.engine.RequestValidator;
import net.rcwalter.ashlar.engine.RestoreTask;
import net.rcwalter.ashlar.engine.TickBudgetExecutor;
import net.rcwalter.ashlar.net.ClientSession;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;
import net.rcwalter.ashlar.snapshot.Snapshot;
import net.rcwalter.ashlar.snapshot.SnapshotStore;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Backs the {@code snapshot} / {@code restore} / {@code list_snapshots}
 * methods (spec &sect;3.3, plan.md &sect;3.1: "one class, three {@code
 * RpcHandler} instances"). Each RPC method is exposed as a small {@link
 * RpcHandler}-shaped method reference via {@link #snapshot()}/{@link
 * #restore()}/{@link #listSnapshots()}, registered individually in {@code
 * AshlarPlugin}.
 */
public final class SnapshotHandler {

    private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final PluginConfig config;
    private final TickBudgetExecutor executor;
    private final SnapshotStore store;

    public SnapshotHandler(PluginConfig config, TickBudgetExecutor executor, SnapshotStore store) {
        this.config = config;
        this.executor = executor;
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

    private CompletableFuture<JsonElement> handleSnapshot(ClientSession session, JsonElement id, JsonObject params) {
        if (!config.snapshot().enabled()) {
            return CompletableFuture.failedFuture(new RpcError(ErrorCode.DISABLED, "snapshot is disabled in config.yml"));
        }
        try {
            RequestValidator validator = new RequestValidator(config);
            World world = validator.resolveWorld(params);
            String label = optString(params, "label");
            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                    .thenCompose(heights -> startSnapshot(validator, world, params, heights, label, session, id));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startSnapshot(RequestValidator validator, World world, JsonObject params,
            int[] heights, String label, ClientSession session, JsonElement id) {
        try {
            Region region = validator.validateReadRegion(params, heights[0], heights[1], config.snapshot().maxVolume());
            ReadTask readTask = new ReadTask(region, world);
            String snapshotId = generateId();
            return executor.submit(readTask, session, id).thenApply(ignoredReadJson -> {
                RegionData data = readTask.regionData();
                Instant createdAt = Instant.now();
                Snapshot snapshot = new Snapshot(snapshotId, world.getName(), region, region.volume(), createdAt, label, data);
                store.put(snapshot);
                return (JsonElement) snapshotResultJson(snapshot);
            });
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static JsonObject snapshotResultJson(Snapshot s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id());
        o.addProperty("world", s.world());
        o.add("from", intArray(s.region().minX(), s.region().minY(), s.region().minZ()));
        o.add("to", intArray(s.region().maxX(), s.region().maxY(), s.region().maxZ()));
        o.addProperty("volume", s.volume());
        o.addProperty("createdAt", s.createdAt().toString());
        if (s.label() != null) {
            o.addProperty("label", s.label());
        }
        return o;
    }

    // ------------------------------------------------------------------
    // restore
    // ------------------------------------------------------------------

    private CompletableFuture<JsonElement> handleRestore(ClientSession session, JsonElement id, JsonObject params) {
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

            RestoreTask task = new RestoreTask(snapshot.region(), world, data, paletteBlocks,
                    config.engine().connectBlocks(), config.engine().supportWarnings());
            return executor.submit(task, session, id).thenApply(resultJson -> {
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

    private CompletableFuture<JsonElement> handleListSnapshots(ClientSession session, JsonElement id, JsonObject params) {
        JsonArray arr = new JsonArray();
        for (Snapshot s : store.listNewestFirst()) {
            arr.add(snapshotResultJson(s));
        }
        JsonObject result = new JsonObject();
        result.add("snapshots", arr);
        return CompletableFuture.completedFuture(result);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** {@code snap-yyyyMMdd-HHmmss-xxxx} (plan.md &sect;3.1), UTC timestamp + 4 random hex digits. */
    private static String generateId() {
        String stamp = ID_TIMESTAMP.format(Instant.now());
        String suffix = String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
        return "snap-" + stamp + "-" + suffix;
    }

    private static JsonArray intArray(int... values) {
        JsonArray arr = new JsonArray();
        for (int v : values) {
            arr.add(v);
        }
        return arr;
    }

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
