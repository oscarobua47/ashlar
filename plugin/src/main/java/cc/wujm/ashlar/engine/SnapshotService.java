// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.snapshot.Snapshot;
import cc.wujm.ashlar.snapshot.SnapshotStore;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The execution path behind {@code snapshot}/{@code restore} (spec
 * &sect;3.3, plan.md &sect;3.1), split out of {@code SnapshotHandler}
 * (plan.md step7). Callers must have already validated params: {@code
 * snapshot} resolves the world and region; {@code restore} looks up the
 * {@link Snapshot} by id, checks the world still exists, checks the chunk
 * count and parses every palette entry, per plan.md &sect;3.1 ("if one
 * palette entry fails to parse, the whole restore is rejected").
 * {@code list_snapshots} has no execution step to split (it only reads the
 * in-memory {@link SnapshotStore}) and is left entirely in the handler.
 */
public final class SnapshotService {

    private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ConfigHolder configHolder;
    private final TickBudgetExecutor executor;
    private final SnapshotStore store;

    public SnapshotService(ConfigHolder configHolder, TickBudgetExecutor executor, SnapshotStore store) {
        this.configHolder = configHolder;
        this.executor = executor;
        this.store = store;
    }

    /**
     * Reads {@code region} and stores it as a new {@link Snapshot}, owned by {@code ctx.principal()}
     * when it is a {@link InvocationContext.Kind#PLAYER} (a real player's or {@code ashlar
     * simulate}'s request through the embedded agent - {@link cc.wujm.ashlar.agent.AgentRunner}
     * always builds a {@code PLAYER} context for every tool call it makes) - {@code null} otherwise
     * (an MCP client's {@code tool_call}/{@code snapshot} always carries a {@code WS_TOKEN}
     * context, step8l-prompt.md &sect;A: "an MCP client's snapshots have no owner"). Must not be
     * called from the main thread.
     */
    public CompletableFuture<JsonElement> snapshot(World world, Region region, String label, InvocationContext ctx) {
        MainThread.assertNotPrimary("SnapshotService.snapshot");
        ReadTask readTask = new ReadTask(region, world);
        String snapshotId = generateId();
        java.util.UUID owner = ctx.principal().kind() == InvocationContext.Kind.PLAYER
                ? java.util.UUID.fromString(ctx.principal().id()) : null;
        return executor.submit(readTask, ctx).thenApply(ignoredReadJson -> {
            RegionData data = readTask.regionData();
            Instant createdAt = Instant.now();
            Snapshot snapshot = new Snapshot(snapshotId, world.getName(), region, region.volume(), createdAt, label, data, owner);
            store.put(snapshot);
            return (JsonElement) snapshotResultJson(snapshot);
        });
    }

    /**
     * Replays {@code snapshot}'s data back onto {@code world}. Returns the raw {@code restore} result JSON
     * ({@code changed}/{@code queuedMs}/{@code elapsedMs}, per {@link RestoreTask#buildResult}); the caller adds
     * {@code id}. Must not be called from the main thread.
     */
    public CompletableFuture<JsonElement> restore(Snapshot snapshot, World world, BlockData[] paletteBlocks, InvocationContext ctx) {
        MainThread.assertNotPrimary("SnapshotService.restore");
        PluginConfig.EngineConfig engineConfig = configHolder.get().engine();
        RestoreTask task = new RestoreTask(snapshot.region(), world, snapshot.data(), paletteBlocks,
                engineConfig.connectBlocks(), engineConfig.supportWarnings());
        return executor.submit(task, ctx);
    }

    /** {@code snapshot}/{@code restore} result shape for one {@link Snapshot} (also used by {@code list_snapshots}). */
    public static JsonObject snapshotResultJson(Snapshot s) {
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
}
