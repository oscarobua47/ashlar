// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.command;

import cc.wujm.ashlar.snapshot.Snapshot;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bookkeeping behind {@code /ashlar undo} (step8l-prompt.md &sect;B): which snapshots have
 * already been undone, so a second {@code /ashlar undo} walks one step further back instead of
 * restoring the same snapshot again. Pure Java, no Bukkit - {@link AshlarCommand} is the only
 * caller, passing it the caller's own snapshots from {@link
 * cc.wujm.ashlar.snapshot.SnapshotStore#forOwnerNewestFirst}, newest first.
 *
 * <p>One shared instance covers every player: snapshot ids ({@code snap-yyyyMMdd-HHmmss-xxxx})
 * are already globally unique, so a flat {@link Set} of "already undone" ids is enough - no
 * per-player keying needed. In-memory only: undo history does not need to survive a plugin
 * restart (a restart also drops the running Bukkit world state an undo would otherwise need to
 * make sense of), and the snapshots it points at are already bounded by {@code
 * snapshot.max-snapshots}.
 */
public final class UndoTracker {

    private final Set<String> undoneIds = ConcurrentHashMap.newKeySet();

    /**
     * The newest snapshot in {@code ownerSnapshotsNewestFirst} that has not already been undone,
     * or empty if every one of them has been (including "the list is empty" - nothing owned at
     * all). Does not itself mark anything as undone; call {@link #markUndone} once the restore
     * this returns actually succeeds.
     */
    public Optional<Snapshot> next(List<Snapshot> ownerSnapshotsNewestFirst) {
        for (Snapshot s : ownerSnapshotsNewestFirst) {
            if (!undoneIds.contains(s.id())) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    public void markUndone(String snapshotId) {
        undoneIds.add(snapshotId);
    }
}
