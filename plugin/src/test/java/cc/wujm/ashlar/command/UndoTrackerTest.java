// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.command;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;
import cc.wujm.ashlar.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link UndoTracker} (step8l-prompt.md &sect;C): pure Java, no Bukkit. Exercises
 * the "already undone" bookkeeping directly - {@link cc.wujm.ashlar.snapshot.SnapshotStoreTest}
 * covers the owner-filtering ({@link cc.wujm.ashlar.snapshot.SnapshotStore#forOwnerNewestFirst})
 * that feeds it.
 */
class UndoTrackerTest {

    private static final Region REGION = new Region(0, 60, 0, 1, 61, 1);
    private static final RegionData DATA = new RegionData(REGION, List.of("minecraft:stone"), new int[]{0}, new int[]{8});
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static Snapshot snapshot(String id, Instant createdAt) {
        return new Snapshot(id, "world", REGION, REGION.volume(), createdAt, null, DATA, OWNER);
    }

    @Test
    void emptyListIsNothingToUndo() {
        UndoTracker tracker = new UndoTracker();
        assertEquals(Optional.empty(), tracker.next(List.of()));
    }

    @Test
    void newestFirstListReturnsTheFirstEntry() {
        UndoTracker tracker = new UndoTracker();
        Snapshot newest = snapshot("snap-2", Instant.parse("2026-01-02T00:00:00Z"));
        Snapshot older = snapshot("snap-1", Instant.parse("2026-01-01T00:00:00Z"));
        Optional<Snapshot> next = tracker.next(List.of(newest, older));
        assertTrue(next.isPresent());
        assertEquals("snap-2", next.get().id());
    }

    @Test
    void undoingTwiceWalksTwoStepsBack() {
        UndoTracker tracker = new UndoTracker();
        Snapshot third = snapshot("snap-3", Instant.parse("2026-01-03T00:00:00Z"));
        Snapshot second = snapshot("snap-2", Instant.parse("2026-01-02T00:00:00Z"));
        Snapshot first = snapshot("snap-1", Instant.parse("2026-01-01T00:00:00Z"));
        List<Snapshot> newestFirst = List.of(third, second, first);

        Optional<Snapshot> firstUndo = tracker.next(newestFirst);
        assertTrue(firstUndo.isPresent());
        assertEquals("snap-3", firstUndo.get().id());
        tracker.markUndone(firstUndo.get().id());

        Optional<Snapshot> secondUndo = tracker.next(newestFirst);
        assertTrue(secondUndo.isPresent());
        assertEquals("snap-2", secondUndo.get().id());
        tracker.markUndone(secondUndo.get().id());

        Optional<Snapshot> thirdUndo = tracker.next(newestFirst);
        assertTrue(thirdUndo.isPresent());
        assertEquals("snap-1", thirdUndo.get().id());
    }

    @Test
    void undoingEveryOwnedSnapshotLeavesNothingToUndo() {
        UndoTracker tracker = new UndoTracker();
        Snapshot only = snapshot("snap-only", Instant.now());
        tracker.markUndone(only.id());
        assertEquals(Optional.empty(), tracker.next(List.of(only)));
    }

    @Test
    void markUndoneOnAnUnrelatedIdDoesNotAffectOthers() {
        UndoTracker tracker = new UndoTracker();
        tracker.markUndone("snap-elsewhere");
        Snapshot only = snapshot("snap-mine", Instant.now());
        Optional<Snapshot> next = tracker.next(List.of(only));
        assertTrue(next.isPresent());
        assertEquals("snap-mine", next.get().id());
    }
}
