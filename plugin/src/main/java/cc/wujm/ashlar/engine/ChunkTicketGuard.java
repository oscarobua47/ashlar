// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Force-loads the chunks covering a {@link Region} for the duration of a
 * build task, so world edits outside players' loaded chunks do not silently
 * no-op (spec &sect;3.5 point 4). Must be constructed and released on the
 * main thread: {@code World#addPluginChunkTicket}/{@code removePluginChunkTicket}
 * are Bukkit {@code World} methods. {@link #release()} is idempotent and
 * must be called from a {@code finally}-equivalent path by the caller.
 */
public final class ChunkTicketGuard {

    private final World world;
    private final Plugin plugin;
    private final Iterable<Region.ChunkCoord> coords;
    private boolean released = false;

    public ChunkTicketGuard(World world, Region region, Plugin plugin) {
        this.world = world;
        this.plugin = plugin;
        this.coords = region.chunkCoords();
        for (Region.ChunkCoord c : coords) {
            world.addPluginChunkTicket(c.cx(), c.cz(), plugin);
        }
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        for (Region.ChunkCoord c : coords) {
            world.removePluginChunkTicket(c.cx(), c.cz(), plugin);
        }
    }
}
