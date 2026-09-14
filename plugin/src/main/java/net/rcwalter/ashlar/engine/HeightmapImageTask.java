// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes the read side of a {@code render} request with {@code view:
 * "heightmap"} (docs/prompts/step4f-prompt.md, updated by
 * docs/prompts/step4g-prompt.md Bug 1): for every {@code (x,z)} in the
 * requested area, reads the requested heightmap type's height and
 * classifies the surface block there via {@link SurfaceClass} - ground,
 * liquid, or vegetation. Liquid cells get one extra {@code OCEAN_FLOOR}
 * read (for shading depth); vegetation cells are excluded from min/max so a
 * tree canopy's trunk-top height cannot skew the band scale.
 *
 * <p>Same read/self-correction logic as {@link HeightmapTask} (see that
 * class's javadoc for the "surface block's own y" contract), duplicated
 * rather than shared because this task also tracks the classes/liquid-depth
 * arrays; {@link SurfaceStats} factors out the one piece that actually was
 * shared (the surface-material-count JSON).
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

    private final int[][] heights;      // [zi][xi] requested type
    private final int[][] classes;      // [zi][xi] SurfaceClass.code()
    private final int[][] liquidDepth;  // [zi][xi] only meaningful where classes == LIQUID
    private final Map<String, Long> surfaceCounts = new LinkedHashMap<>();
    private int min = Integer.MAX_VALUE;
    private int max = Integer.MIN_VALUE;
    private long liquidCells = 0;
    private long vegetationCells = 0;

    private int cursorZ;
    private int cursorX;
    private boolean cursorInitialized = false;

    public HeightmapImageTask(Region region, World world, int x1, int z1, int x2, int z2, HeightMap requestedMap) {
        super(region);
        this.world = world;
        this.x1 = x1;
        this.z1 = z1;
        this.x2 = x2;
        this.z2 = z2;
        this.requestedMap = requestedMap;
        this.heights = new int[z2 - z1 + 1][x2 - x1 + 1];
        this.classes = new int[z2 - z1 + 1][x2 - x1 + 1];
        this.liquidDepth = new int[z2 - z1 + 1][x2 - x1 + 1];
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
        int sinceCheck = 0;
        while (cursorX <= x2) {
            int y = readHeight(cursorX, cursorZ, requestedMap);
            int zi = cursorZ - z1, xi = cursorX - x1;
            heights[zi][xi] = y;

            BlockData data = world.getBlockAt(cursorX, y, cursorZ).getBlockData();
            boolean waterlogged = data instanceof Waterlogged w && w.isWaterlogged();
            SurfaceClass cls = SurfaceClass.classify(data.getMaterial().name(), waterlogged);
            classes[zi][xi] = cls.code();

            if (cls == SurfaceClass.LIQUID) {
                int floorY = readHeight(cursorX, cursorZ, HeightMap.OCEAN_FLOOR);
                liquidDepth[zi][xi] = Math.max(0, y - floorY);
                liquidCells++;
            }
            if (cls == SurfaceClass.VEGETATION) {
                vegetationCells++;
            } else {
                min = Math.min(min, y);
                max = Math.max(max, y);
            }

            String materialKey = data.getMaterial().getKey().toString();
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

    /** [zi][xi] height of the requested heightmap type (trunk top for vegetation cells). Only populated once {@link #step} has returned {@code true}. */
    public int[][] heights() {
        return heights;
    }

    /** [zi][xi] {@link SurfaceClass#code()}: 0 ground, 1 liquid, 2 vegetation. */
    public int[][] classes() {
        return classes;
    }

    /** [zi][xi] liquid depth (requested height minus OCEAN_FLOOR height), only meaningful where {@link #classes()} is liquid. */
    public int[][] liquidDepth() {
        return liquidDepth;
    }

    /** Number of cells classified as liquid. */
    public long liquidCells() {
        return liquidCells;
    }

    /** Number of cells classified as vegetation (trees etc). */
    public long vegetationCells() {
        return vegetationCells;
    }

    /** Min surface height, excluding vegetation cells (falls back to the raw min if every cell is vegetation). */
    public int min() {
        return min <= max ? min : rawExtreme(true);
    }

    /** Max surface height, excluding vegetation cells (falls back to the raw max if every cell is vegetation). */
    public int max() {
        return min <= max ? max : rawExtreme(false);
    }

    private int rawExtreme(boolean wantMin) {
        int m = wantMin ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        for (int[] row : heights) {
            for (int h : row) {
                m = wantMin ? Math.min(m, h) : Math.max(m, h);
            }
        }
        return m;
    }

    public JsonObject surfaceJson() {
        return SurfaceStats.buildSurfaceJson(surfaceCounts);
    }
}
