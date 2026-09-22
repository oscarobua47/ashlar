// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.snapshot;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SnapshotStore} (step8l-prompt.md &sect;A/C): {@link
 * SnapshotStore#forOwnerNewestFirst} - owner filtering, newest-first order, no-owner (MCP)
 * snapshots excluded - and that a pre-0.4.9 snapshot file with no {@code ownerUuid} key still
 * loads, with {@link Snapshot#ownerUuid()} coming back {@code null}. No Bukkit.
 */
class SnapshotStoreTest {

    private static final Logger LOGGER = Logger.getLogger("SnapshotStoreTest");
    private static final Region REGION = new Region(0, 60, 0, 1, 61, 1);
    private static final RegionData DATA = new RegionData(REGION, List.of("minecraft:stone"), new int[]{0}, new int[]{8});
    private static final UUID PLAYER_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Snapshot snapshot(String id, Instant createdAt, UUID owner) {
        return new Snapshot(id, "world", REGION, REGION.volume(), createdAt, null, DATA, owner);
    }

    @Test
    void forOwnerNewestFirstReturnsOnlyThatOwnersSnapshotsNewestFirst(@TempDir Path tempDir) {
        SnapshotStore store = new SnapshotStore(tempDir, 20, LOGGER);
        store.put(snapshot("snap-1", Instant.parse("2026-01-01T00:00:00Z"), PLAYER_1));
        store.put(snapshot("snap-2", Instant.parse("2026-01-02T00:00:00Z"), PLAYER_2));
        store.put(snapshot("snap-3", Instant.parse("2026-01-03T00:00:00Z"), PLAYER_1));
        // An MCP client's snapshot: no owner at all.
        store.put(snapshot("snap-mcp", Instant.parse("2026-01-04T00:00:00Z"), null));

        List<Snapshot> owned = store.forOwnerNewestFirst(PLAYER_1);
        assertEquals(2, owned.size());
        assertEquals("snap-3", owned.get(0).id());
        assertEquals("snap-1", owned.get(1).id());
    }

    @Test
    void forOwnerNewestFirstNeverReturnsAnUnownedSnapshot(@TempDir Path tempDir) {
        SnapshotStore store = new SnapshotStore(tempDir, 20, LOGGER);
        store.put(snapshot("snap-mcp-1", Instant.now(), null));
        store.put(snapshot("snap-mcp-2", Instant.now(), null));

        assertTrue(store.forOwnerNewestFirst(PLAYER_1).isEmpty());
    }

    @Test
    void forOwnerNewestFirstIsEmptyWhenTheStoreIsEmpty(@TempDir Path tempDir) {
        SnapshotStore store = new SnapshotStore(tempDir, 20, LOGGER);
        assertTrue(store.forOwnerNewestFirst(PLAYER_1).isEmpty());
    }

    /**
     * A snapshot created and persisted with an owner round-trips through disk with that owner
     * intact (the flip side of the legacy-file test below: the field is not just accepted when
     * absent, it is actually written and read back).
     */
    @Test
    void ownedSnapshotSurvivesADiskRoundTrip(@TempDir Path tempDir) throws InterruptedException {
        SnapshotStore writer = new SnapshotStore(tempDir, 20, LOGGER);
        writer.loadFromDisk(); // creates the snapshots/ directory the async gzip write needs
        writer.put(snapshot("snap-owned", Instant.parse("2026-01-01T00:00:00Z"), PLAYER_1));
        writer.shutdown(); // drains the async gzip write

        SnapshotStore reader = new SnapshotStore(tempDir, 20, LOGGER);
        reader.loadFromDisk();
        Snapshot loaded = reader.get("snap-owned").orElseThrow();
        assertEquals(PLAYER_1, loaded.ownerUuid());
        assertEquals(1, reader.forOwnerNewestFirst(PLAYER_1).size());
    }

    /**
     * Hand-writes a snapshot file in the pre-0.4.9 shape (no {@code ownerUuid} key at all, exactly
     * what every snapshot file written before step8l-prompt.md looks like) and confirms it still
     * loads: {@link Snapshot#ownerUuid()} comes back {@code null} rather than the load failing or
     * throwing, and such a snapshot is correctly excluded from every player's undo history.
     */
    @Test
    void legacySnapshotFileWithNoOwnerFieldLoadsWithNullOwner(@TempDir Path tempDir) throws IOException {
        Path snapshotsDir = tempDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);
        String legacyJson = "{"
                + "\"id\":\"snap-legacy\","
                + "\"world\":\"world\","
                + "\"region\":{\"from\":[0,60,0],\"to\":[1,61,1]},"
                + "\"volume\":8,"
                + "\"createdAt\":\"2025-06-01T00:00:00Z\","
                + "\"data\":{\"bounds\":{\"from\":[0,60,0],\"to\":[1,61,1]},\"order\":\"y,z,x\","
                + "\"palette\":[\"minecraft:stone\"],\"runs\":[[0,8]],\"volume\":8}"
                + "}";
        try (OutputStream out = Files.newOutputStream(snapshotsDir.resolve("snap-legacy.json.gz"));
                GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(legacyJson.getBytes(StandardCharsets.UTF_8));
        }

        SnapshotStore store = new SnapshotStore(tempDir, 20, LOGGER);
        store.loadFromDisk();

        Snapshot loaded = store.get("snap-legacy").orElseThrow();
        assertNull(loaded.ownerUuid());
        assertTrue(store.forOwnerNewestFirst(PLAYER_1).isEmpty());
        assertTrue(store.forOwnerNewestFirst(PLAYER_2).isEmpty());
    }
}
