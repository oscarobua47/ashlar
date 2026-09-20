// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the neighbour-check candidate list consumed by {@link SupportCheck}
 * (docs/prompts/step4i-prompt.md): positions that were <em>not</em> written
 * by the task itself but sit right outside a region (or single cell) that
 * just turned non-solid, and so may have lost the support a neighbouring
 * block was relying on - vanilla's own neighbour-update chain, which {@code
 * fill_batch}/{@code set_blocks}/{@code restore} deliberately skip by
 * writing with {@code physics=false}.
 *
 * <p>Only a rule that {@link SupportCheck} actually implements ever looks
 * more than one block away from a candidate, and every one of those rules
 * checks a single axis-aligned neighbour (facing, above, below, or the four
 * horizontal sides for the embedded-torch case) - never a diagonal. So the
 * six rectangular faces directly touching a region are the complete set of
 * positions whose relevant neighbour could land inside that region; the
 * expanded box's edges/corners are deliberately not included, keeping this
 * strictly O(surface), never O(volume), even for a region spanning the
 * {@link #MAX_POSITIONS} cap's worth of surface area.
 */
final class NeighbourPositions {

    /**
     * Above this many candidates, stop adding and flag the list as
     * truncated. A 500,000-block clear's cube-shaped shell is at most
     * ~40,000 positions; this only bites on deliberately thin or sprawling
     * shapes (docs/prompts/step4i-prompt.md).
     */
    static final int MAX_POSITIONS = 200_000;

    private final List<int[]> positions = new ArrayList<>();
    private boolean truncated = false;

    List<int[]> positions() {
        return positions;
    }

    boolean truncated() {
        return truncated;
    }

    /**
     * Adds the six faces directly touching {@code region} (one block outside
     * each face, spanning only that face's own extent - no corners), skipping
     * any face whose constant coordinate would fall outside {@code
     * [minHeight, maxHeight)}. Positions inside {@code region} are never
     * added: they were just (over)written and are already covered by the
     * task's own written-position pass.
     */
    void addShell(Region region, int minHeight, int maxHeight) {
        if (truncated) {
            return;
        }
        int minX = region.minX();
        int maxX = region.maxX();
        int minY = region.minY();
        int maxY = region.maxY();
        int minZ = region.minZ();
        int maxZ = region.maxZ();

        if (!addXFace(minX - 1, minY, maxY, minZ, maxZ)) {
            return;
        }
        if (!addXFace(maxX + 1, minY, maxY, minZ, maxZ)) {
            return;
        }
        if (!addZFace(minZ - 1, minX, maxX, minY, maxY)) {
            return;
        }
        if (!addZFace(maxZ + 1, minX, maxX, minY, maxY)) {
            return;
        }
        int belowY = minY - 1;
        if (belowY >= minHeight && !addYFace(belowY, minX, maxX, minZ, maxZ)) {
            return;
        }
        int aboveY = maxY + 1;
        if (aboveY <= maxHeight - 1) {
            addYFace(aboveY, minX, maxX, minZ, maxZ);
        }
    }

    /** Adds the six neighbours of a single cell, skipping any outside {@code [minHeight, maxHeight)}. */
    void addNeighbours(int x, int y, int z, int minHeight, int maxHeight) {
        if (truncated) {
            return;
        }
        if (!add(x + 1, y, z)) return;
        if (!add(x - 1, y, z)) return;
        if (y + 1 <= maxHeight - 1 && !add(x, y + 1, z)) return;
        if (y - 1 >= minHeight && !add(x, y - 1, z)) return;
        if (!add(x, y, z + 1)) return;
        add(x, y, z - 1);
    }

    private boolean addXFace(int x, int minY, int maxY, int minZ, int maxZ) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!add(x, y, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean addZFace(int z, int minX, int maxX, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (!add(x, y, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean addYFace(int y, int minX, int maxX, int minZ, int maxZ) {
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (!add(x, y, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean add(int x, int y, int z) {
        if (positions.size() >= MAX_POSITIONS) {
            truncated = true;
            return false;
        }
        positions.add(new int[]{x, y, z});
        return true;
    }
}
