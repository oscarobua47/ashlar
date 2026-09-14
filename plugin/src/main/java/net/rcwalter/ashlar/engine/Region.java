// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An inclusive-bounds rectangular region ({@code from}/{@code to} both
 * inclusive, per spec &sect;6 point 7: closed coordinate ranges). {@link #of} normalizes
 * an arbitrary from/to corner pair so callers never have to worry about
 * which corner is the min and which is the max.
 */
public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Region of(int[] from, int[] to) {
        return new Region(
                Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2]),
                Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2]));
    }

    /** Inclusive-bounds volume: {@code (dx)*(dy)*(dz)}, computed in {@code long} to avoid overflow. */
    public long volume() {
        long dx = (long) maxX - minX + 1;
        long dy = (long) maxY - minY + 1;
        long dz = (long) maxZ - minZ + 1;
        return dx * dy * dz;
    }

    /** The set of chunk coordinates (block coordinate {@code >> 4}) this region overlaps. */
    public Set<ChunkCoord> chunkCoords() {
        Set<ChunkCoord> coords = new LinkedHashSet<>();
        int minCx = minX >> 4;
        int maxCx = maxX >> 4;
        int minCz = minZ >> 4;
        int maxCz = maxZ >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                coords.add(new ChunkCoord(cx, cz));
            }
        }
        return coords;
    }

    /**
     * Number of chunks (16x16 columns) this region's x/z footprint overlaps,
     * computed in {@code long} to avoid overflow for pathological inputs.
     * Cheaper than {@code chunkCoords().size()} since it needs no set.
     */
    public long chunkCount() {
        long chunksX = (long) (maxX >> 4) - (minX >> 4) + 1;
        long chunksZ = (long) (maxZ >> 4) - (minZ >> 4) + 1;
        return chunksX * chunksZ;
    }

    public record ChunkCoord(int cx, int cz) {
    }
}
