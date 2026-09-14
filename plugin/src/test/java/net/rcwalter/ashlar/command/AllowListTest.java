// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.command;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AllowList} (step6e-prompt.md). Pure Java, no
 * Bukkit; persistence is exercised against a real temp file so a second
 * instance pointed at the same path proves the round trip.
 */
class AllowListTest {

    private static final Logger LOGGER = Logger.getLogger(AllowListTest.class.getName());

    @Test
    void startsEmpty(@TempDir Path dir) {
        AllowList list = new AllowList(dir.resolve("allowed-players.yml"), LOGGER);
        assertTrue(list.names().isEmpty());
        assertFalse(list.contains("Alex"));
    }

    @Test
    void addThenContainsCaseInsensitively(@TempDir Path dir) {
        AllowList list = new AllowList(dir.resolve("allowed-players.yml"), LOGGER);

        assertTrue(list.add("Alex"));
        assertTrue(list.contains("Alex"));
        assertTrue(list.contains("alex"));
        assertTrue(list.contains("ALEX"));
        assertFalse(list.contains("Steve"));
    }

    @Test
    void addingTwiceIsANoOp(@TempDir Path dir) {
        AllowList list = new AllowList(dir.resolve("allowed-players.yml"), LOGGER);

        assertTrue(list.add("Alex"));
        assertFalse(list.add("alex"));
        assertEquals(List.of("Alex"), list.names());
    }

    @Test
    void removeIsCaseInsensitiveAndReportsWhetherItExisted(@TempDir Path dir) {
        AllowList list = new AllowList(dir.resolve("allowed-players.yml"), LOGGER);
        list.add("Alex");

        assertTrue(list.remove("ALEX"));
        assertFalse(list.contains("Alex"));
        assertFalse(list.remove("Alex"));
    }

    @Test
    void namesPreservesInsertionOrderAsTyped(@TempDir Path dir) {
        AllowList list = new AllowList(dir.resolve("allowed-players.yml"), LOGGER);
        list.add("Steve");
        list.add("Alex");

        assertEquals(List.of("Steve", "Alex"), list.names());
    }

    @Test
    void persistsAcrossInstances(@TempDir Path dir) {
        Path path = dir.resolve("allowed-players.yml");
        AllowList first = new AllowList(path, LOGGER);
        first.add("Steve");
        first.add("Alex");

        AllowList second = new AllowList(path, LOGGER);
        second.load();

        assertEquals(List.of("Steve", "Alex"), second.names());
    }

    @Test
    void removalPersists(@TempDir Path dir) {
        Path path = dir.resolve("allowed-players.yml");
        AllowList first = new AllowList(path, LOGGER);
        first.add("Steve");
        first.add("Alex");
        first.remove("Steve");

        AllowList second = new AllowList(path, LOGGER);
        second.load();

        assertEquals(List.of("Alex"), second.names());
    }

    @Test
    void loadWithNoFileLeavesListEmpty(@TempDir Path dir) {
        AllowList list = new AllowList(dir.resolve("does-not-exist.yml"), LOGGER);
        list.load();
        assertTrue(list.names().isEmpty());
    }
}
