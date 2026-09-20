// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

/**
 * Pure-geometry predicate for {@link FillMode#WALLS}, extracted into its own
 * class (no Bukkit dependency, unlike {@link FillTask}) so it can be unit
 * tested without a server.
 */
public final class WallGeometry {

    private WallGeometry() {
    }

    /**
     * True when {@code (x,z)} lies on one of the region's four vertical
     * sides, independent of {@code y}: {@code x == minX || x == maxX || z ==
     * minZ || z == maxZ}.
     */
    public static boolean isWallCell(int x, int z, Region region) {
        return x == region.minX() || x == region.maxX() || z == region.minZ() || z == region.maxZ();
    }
}
