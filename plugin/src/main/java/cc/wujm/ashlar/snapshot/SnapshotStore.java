// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.snapshot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * In-memory + on-disk store for {@link Snapshot}s (plan.md &sect;3.1). The
 * in-memory map is a {@link LinkedHashMap} in insertion (== creation) order
 * so the oldest entry is always {@code values().iterator().next()}; disk
 * files live at {@code plugins/Ashlar/snapshots/<id>.json.gz}.
 *
 * <p>Loads every existing snapshot from disk on construction/{@link
 * #loadFromDisk()} (logs the count). {@link #put} evicts the oldest entries
 * beyond {@code snapshot.max-snapshots} from both memory and disk. All gzip
 * I/O happens on a dedicated single-thread daemon executor, never the main
 * thread; {@link #shutdown()} drains it on plugin disable.
 */
public final class SnapshotStore {

    private final Path snapshotsDir;
    private final int maxSnapshots;
    private final Logger logger;
    private final LinkedHashMap<String, Snapshot> byId = new LinkedHashMap<>();
    private final Object lock = new Object();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ashlar-snapshot-io");
        t.setDaemon(true);
        return t;
    });

    public SnapshotStore(Path dataFolder, int maxSnapshots, Logger logger) {
        this.snapshotsDir = dataFolder.resolve("snapshots");
        this.maxSnapshots = maxSnapshots;
        this.logger = logger;
    }

    /** Loads every {@code *.json.gz} snapshot from disk. Call once from {@code onEnable}. */
    public void loadFromDisk() {
        try {
            Files.createDirectories(snapshotsDir);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to create snapshots directory", e);
            return;
        }
        List<Path> files;
        try (var stream = Files.list(snapshotsDir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".json.gz")).sorted().toList();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to list snapshots directory", e);
            return;
        }
        int loaded = 0;
        synchronized (lock) {
            for (Path file : files) {
                try {
                    Snapshot snapshot = readSnapshotFile(file);
                    byId.put(snapshot.id(), snapshot);
                    loaded++;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to load snapshot file " + file.getFileName() + "; skipping it", e);
                }
            }
        }
        logger.info("Loaded " + loaded + " snapshot(s) from disk");
    }

    /** Stores a new snapshot, evicting the oldest beyond {@code maxSnapshots} from memory immediately and disk asynchronously. */
    public void put(Snapshot snapshot) {
        List<Snapshot> evicted = new ArrayList<>();
        synchronized (lock) {
            byId.put(snapshot.id(), snapshot);
            Iterator<Snapshot> it = byId.values().iterator();
            while (byId.size() > maxSnapshots && it.hasNext()) {
                evicted.add(it.next());
                it.remove();
            }
        }
        ioExecutor.submit(() -> {
            writeSnapshotFile(snapshot);
            for (Snapshot old : evicted) {
                deleteSnapshotFile(old.id());
            }
        });
    }

    public Optional<Snapshot> get(String id) {
        synchronized (lock) {
            return Optional.ofNullable(byId.get(id));
        }
    }

    /** All snapshots, newest {@code createdAt} first. */
    public List<Snapshot> listNewestFirst() {
        synchronized (lock) {
            List<Snapshot> list = new ArrayList<>(byId.values());
            list.sort(Comparator.comparing(Snapshot::createdAt).reversed());
            return list;
        }
    }

    /** Drains the I/O executor so a shutdown never truncates a still-in-flight gzip write. Call from {@code onDisable}. */
    public void shutdown() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }

    private void writeSnapshotFile(Snapshot snapshot) {
        Path file = snapshotsDir.resolve(snapshot.id() + ".json.gz");
        JsonObject json = toJson(snapshot);
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to write snapshot file for " + snapshot.id(), e);
        }
    }

    private void deleteSnapshotFile(String id) {
        try {
            Files.deleteIfExists(snapshotsDir.resolve(id + ".json.gz"));
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to delete evicted snapshot file for " + id, e);
        }
    }

    private Snapshot readSnapshotFile(Path file) throws IOException {
        byte[] raw;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            raw = in.readAllBytes();
        }
        JsonObject json = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        return fromJson(json);
    }

    private static JsonObject toJson(Snapshot s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id());
        o.addProperty("world", s.world());
        o.add("region", regionToJson(s.region()));
        o.addProperty("volume", s.volume());
        o.addProperty("createdAt", s.createdAt().toString());
        if (s.label() != null) {
            o.addProperty("label", s.label());
        }
        o.add("data", s.data().toJson());
        return o;
    }

    private static Snapshot fromJson(JsonObject o) {
        String id = o.get("id").getAsString();
        String world = o.get("world").getAsString();
        Region region = regionFromJson(o.getAsJsonObject("region"));
        long volume = o.get("volume").getAsLong();
        Instant createdAt = Instant.parse(o.get("createdAt").getAsString());
        String label = (o.has("label") && !o.get("label").isJsonNull()) ? o.get("label").getAsString() : null;
        RegionData data = RegionData.fromJson(o.getAsJsonObject("data"));
        return new Snapshot(id, world, region, volume, createdAt, label, data);
    }

    private static JsonObject regionToJson(Region r) {
        JsonObject o = new JsonObject();
        JsonArray from = new JsonArray();
        from.add(r.minX());
        from.add(r.minY());
        from.add(r.minZ());
        JsonArray to = new JsonArray();
        to.add(r.maxX());
        to.add(r.maxY());
        to.add(r.maxZ());
        o.add("from", from);
        o.add("to", to);
        return o;
    }

    private static Region regionFromJson(JsonObject o) {
        JsonArray from = o.getAsJsonArray("from");
        JsonArray to = o.getAsJsonArray("to");
        return new Region(
                from.get(0).getAsInt(), from.get(1).getAsInt(), from.get(2).getAsInt(),
                to.get(0).getAsInt(), to.get(1).getAsInt(), to.get(2).getAsInt());
    }
}
