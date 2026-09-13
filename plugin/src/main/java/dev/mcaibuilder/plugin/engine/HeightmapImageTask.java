// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.bukkit.HeightMap;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes the read side of a {@code render} request with {@code view:
 * "heightmap"} (docs/prompts/step4f-prompt.md): for every {@code (x,z)} in
 * the requested area, reads both the requested heightmap type's height and
 * the {@code SOLID} height in the same main-thread pass - cells where the
 * two differ are liquid, exactly the comparison {@code mc_survey} used to
 * make client-side via two separate {@code heightmap} RPC calls (see
 * {@code mcp-server/src/render/relief.ts}), now done once, server-side, so
 * the render can carry it too.
 *
 * <p>Same read/self-correction logic as {@link HeightmapTask} (see that
 * class's javadoc for the "surface block's own y" contract), duplicated
 * rather than shared because this task reads two heightmaps per cell and
 * tracks a second array; {@link SurfaceStats} factors out the one piece
 * that actually was shared (the surface-material-count JSON).
 *
 * <p>{@link #buildResult} intentionally returns a throwaway {@link
 * JsonNull}, same pattern as {@link RenderTask}: {@code RenderHandler}
 * reads this task's accessors once its future completes and builds the
 * actual PNG response off the main thread.
 */
public final class HeightmapImageTask extends BuildTask {

    private static final int DEADLINE_CHECK_INTERVAL = 256;

    private final World world;
    private final int x1;
    private final int z1;
    private final int x2;
    private final int z2;
    private final HeightMap requestedMap;
    private final HeightMap solidMap;

    private final int[][] heights;      // [zi][xi] requested type
    private final int[][] solidHeights; // [zi][xi] SOLID
    private final Map<String, Long> surfaceCounts = new LinkedHashMap<>();
    private int min = Integer.MAX_VALUE;
    private int max = Integer.MIN_VALUE;

    private int cursorZ;
    private int cursorX;
    private boolean cursorInitialized = false;

    public HeightmapImageTask(Region region, World world, int x1, int z1, int x2, int z2,
                               HeightMap requestedMap, HeightMap solidMap) {
        super(region);
        this.world = world;
        this.x1 = x1;
        this.z1 = z1;
        this.x2 = x2;
        this.z2 = z2;
        this.requestedMap = requestedMap;
        this.solidMap = solidMap;
        this.heights = new int[z2 - z1 + 1][x2 - x1 + 1];
        this.solidHeights = new int[z2 - z1 + 1][x2 - x1 + 1];
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return (long) (x2 - x1 + 1) * (z2 - z1 + 1);
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
        boolean sameMap = requestedMap == solidMap;
        int sinceCheck = 0;
        while (cursorX <= x2) {
            int y = readHeight(cursorX, cursorZ, requestedMap);
            int sy = sameMap ? y : readHeight(cursorX, cursorZ, solidMap);
            int zi = cursorZ - z1, xi = cursorX - x1;
            heights[zi][xi] = y;
            solidHeights[zi][xi] = sy;
            min = Math.min(min, y);
            max = Math.max(max, y);
            String materialKey = world.getBlockAt(cursorX, y, cursorZ).getType().getKey().toString();
            surfaceCounts.merge(materialKey, 1L, Long::sum);

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

    /** Same self-correcting read as {@link HeightmapTask#readRowSegment}: steps down one block if the reported y is air. */
    private int readHeight(int x, int z, HeightMap heightMap) {
        int rawY = world.getHighestBlockYAt(x, z, heightMap);
        int y = rawY;
        if (y > world.getMinHeight() && world.getBlockAt(x, y, z).getType().isAir()) {
            y--;
        }
        return y;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        return JsonNull.INSTANCE; // real work happens off-thread in RenderHandler once this task's future completes
    }

    /** [zi][xi] height of the requested heightmap type. Only populated once {@link #step} has returned {@code true}. */
    public int[][] heights() {
        return heights;
    }

    /** [zi][xi] SOLID height, for liquid detection (differs from {@link #heights()} wherever a liquid surface was read). */
    public int[][] solidHeights() {
        return solidHeights;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public JsonObject surfaceJson() {
        return SurfaceStats.buildSurfaceJson(surfaceCounts);
    }
}
