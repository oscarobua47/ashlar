// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.snapshot;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;

import java.time.Instant;

/**
 * A stored region snapshot: metadata plus the encoded block data needed to
 * {@code restore} it (spec &sect;3.3, plan.md &sect;3.1). {@code label} is the
 * optional caller-supplied text from the {@code snapshot} request; {@code
 * null} when omitted. Deliberately holds a plain {@link Region} (not a
 * Bukkit {@code World}) plus the world's name as a string, so this record
 * (and therefore {@link SnapshotStore}) has no Bukkit imports and its
 * persisted JSON survives a server restart untouched by loaded-world state.
 */
public record Snapshot(String id, String world, Region region, long volume, Instant createdAt, String label, RegionData data) {
}
