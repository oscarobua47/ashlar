// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Arrays;

/**
 * Executes the read side of a {@code render} {@code view: "top"} request as
 * a column scan rather than a full-volume {@link ReadTask} (docs/prompts/step4g-prompt.md,
 * Bug 2): a top-down view only ever needs the single highest visible block
 * per {@code (x,z)} column, so pricing it - and reading it - by volume was
 * both wrong and wasteful (a 200x130x200 area, well within the area limit,
 * exceeded the 200,000-block volume limit outright).
 *
 * <p>For every column: {@link World#getHighestBlockYAt(int, int, HeightMap)}
 * with {@link HeightMap#WORLD_SURFACE} gives the true top of the world at
 * that column. If that y is below the requested {@code minY}, the column is
 * empty within the requested range. Otherwise this walks down from {@code
 * min(y, maxY)} looking for the first non-air block, for at most {@code
 * maxY - minY + 1} steps (never past {@code minY}) - this is what lets a
 * caller ask for a low ceiling above tall terrain without reading the whole
 * column. The found block's vanilla map color ({@link
 * MapColorResolver#resolve}, reused for the exact same "no map color ->
 * transparent" rule every other view uses) and block-state string are
 * recorded for {@link dev.mcaibuilder.plugin.render.ImageRenderer#renderTop}
 * to paint with the identical north-neighbour shading the old volume-based
 * top view used.
 */
public final class TopViewTask extends BuildTask {

    private static final int DEADLINE_CHECK_INTERVAL = 64;

    private final World world;
    private final int x1;
    private final int z1;
    private final int x2;
    private final int z2;
    private final int minY;
    private final int maxY;
    private final int blocksWide;
    private final int blocksTall;

    private final int[] colorArgb;   // [zi*blocksWide+xi]; 0 = no block found in range (transparent)
    private final String[] blockNames; // block-state string, parallel to colorArgb; null where colorArgb == 0
    private final int[] topY;        // world y of the recorded block; Integer.MIN_VALUE where colorArgb == 0

    private int cursorZ;
    private int cursorX;
    private boolean cursorInitialized = false;

    public TopViewTask(Region region, World world) {
        super(region);
        this.world = world;
        this.x1 = region.minX();
        this.z1 = region.minZ();
        this.x2 = region.maxX();
        this.z2 = region.maxZ();
        this.minY = region.minY();
        this.maxY = region.maxY();
        this.blocksWide = x2 - x1 + 1;
        this.blocksTall = z2 - z1 + 1;
        this.colorArgb = new int[blocksWide * blocksTall];
        this.blockNames = new String[blocksWide * blocksTall];
        this.topY = new int[blocksWide * blocksTall];
        Arrays.fill(topY, Integer.MIN_VALUE);
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return (long) blocksWide * blocksTall;
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (!cursorInitialized) {
            cursorZ = z1;
            cursorX = x1;
            cursorInitialized = true;
        }
        while (cursorZ <= z2) {
            if (!readRowSegment(deadlineNanos)) {
                return false;
            }
            cursorX = x1;
            cursorZ++;
            if (cursorZ <= z2 && System.nanoTime() >= deadlineNanos) {
                return false;
            }
        }
        return true;
    }

    private boolean readRowSegment(long deadlineNanos) {
        int sinceCheck = 0;
        while (cursorX <= x2) {
            scanColumn(cursorX, cursorZ);
            advance(1);
            cursorX++;
            sinceCheck++;
            if (sinceCheck >= DEADLINE_CHECK_INTERVAL) {
                sinceCheck = 0;
                if (cursorX <= x2 && System.nanoTime() >= deadlineNanos) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Reads one column, per the class javadoc's walk-down rule. Leaves the column transparent if nothing qualifies. */
    private void scanColumn(int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        if (y < minY) {
            return; // the world's surface here never reaches into [minY, maxY]
        }
        int checkY = Math.min(y, maxY);
        int maxSteps = maxY - minY + 1;
        int found = -1;
        for (int i = 0; i < maxSteps && checkY >= minY; i++, checkY--) {
            if (!world.getBlockAt(x, checkY, z).getType().isAir()) {
                found = checkY;
                break;
            }
        }
        if (found < 0) {
            return; // every block in [minY, maxY] at this column is air
        }
        Block block = world.getBlockAt(x, found, z);
        String blockString = block.getBlockData().getAsString();
        int argb = MapColorResolver.resolve(blockString);
        if (argb == 0) {
            return; // no vanilla map color (e.g. a rare decorative block): transparent, same as the volume path
        }
        int cell = (z - z1) * blocksWide + (x - x1);
        colorArgb[cell] = argb;
        blockNames[cell] = blockString;
        topY[cell] = found;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        return JsonNull.INSTANCE; // real work happens off-thread in RenderHandler once this task's future completes
    }

    public int[] colorArgb() {
        return colorArgb;
    }

    public String[] blockNames() {
        return blockNames;
    }

    public int[] topY() {
        return topY;
    }

    public int blocksWide() {
        return blocksWide;
    }

    public int blocksTall() {
        return blocksTall;
    }
}
