// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.HeightMap;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a {@code heightmap} request: for every {@code (x,z)} in the
 * requested area, reads {@link World#getHighestBlockYAt(int, int, HeightMap)}
 * and the surface block's type (spec &sect;3.2). Implemented uniformly as a
 * {@link BuildTask}/{@link TickBudgetExecutor} job regardless of area size,
 * per plan.md &sect;3.2 ("simplify by not special-casing small areas").
 *
 * <p>{@code World#getHighestBlockYAt} was empirically verified (see the Step
 * 3 verification report) to already return the surface block's own y on this
 * Paper 26.2 build - not the y one block above it - for every heightmap type
 * this RPC exposes: a 20x20 stone platform at y=70 read back as 70 via
 * {@code OCEAN_FLOOR}, and a single water block placed at y=71 read back as
 * 71 via {@code MOTION_BLOCKING}. No offset is applied for that reason, but
 * as a defensive fallback (Bukkit's exact convention here has changed across
 * versions per spec &sect;3.2) this task still self-corrects if the reported y
 * ever turns out to be air: it steps down one block. That guarantees the
 * "surface block's own y" contract the spec requires even if a future/older
 * server version reverts to the "one above" convention.
 */
public final class HeightmapTask extends BuildTask {

    private static final int DEADLINE_CHECK_INTERVAL = 256;
    private static final int MAX_SURFACE_ENTRIES = 16;

    private final World world;
    private final int x1;
    private final int z1;
    private final int x2;
    private final int z2;
    private final HeightMap heightMap;
    private final String typeName;

    private final int[][] heights; // [zi][xi]
    private final Map<String, Long> surfaceCounts = new LinkedHashMap<>();
    private int min = Integer.MAX_VALUE;
    private int max = Integer.MIN_VALUE;

    private int cursorZ;
    private int cursorX;
    private boolean cursorInitialized = false;

    public HeightmapTask(Region region, World world, int x1, int z1, int x2, int z2, HeightMap heightMap, String typeName) {
        super(region);
        this.world = world;
        this.x1 = x1;
        this.z1 = z1;
        this.x2 = x2;
        this.z2 = z2;
        this.heightMap = heightMap;
        this.typeName = typeName;
        this.heights = new int[z2 - z1 + 1][x2 - x1 + 1];
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
            int rawY = world.getHighestBlockYAt(cursorX, cursorZ, heightMap);
            int y = rawY;
            if (y > world.getMinHeight() && world.getBlockAt(cursorX, y, cursorZ).getType().isAir()) {
                y--;
            }
            heights[cursorZ - z1][cursorX - x1] = y;
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

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        JsonObject result = new JsonObject();
        result.addProperty("world", world.getName());
        result.add("from", intArray(x1, z1));
        result.add("to", intArray(x2, z2));
        result.addProperty("type", typeName);
        result.addProperty("order", "z,x");
        JsonArray heightsJson = new JsonArray();
        for (int[] row : heights) {
            JsonArray rowJson = new JsonArray();
            for (int v : row) {
                rowJson.add(v);
            }
            heightsJson.add(rowJson);
        }
        result.add("heights", heightsJson);
        result.addProperty("min", min);
        result.addProperty("max", max);
        result.add("surface", buildSurfaceJson());
        return result;
    }

    /** Surface material counts sorted descending, capped at {@link #MAX_SURFACE_ENTRIES}; the rest merged into "other". */
    private JsonObject buildSurfaceJson() {
        JsonObject surface = new JsonObject();
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(surfaceCounts.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        long other = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < MAX_SURFACE_ENTRIES) {
                surface.addProperty(sorted.get(i).getKey(), sorted.get(i).getValue());
            } else {
                other += sorted.get(i).getValue();
            }
        }
        if (other > 0) {
            surface.addProperty("other", other);
        }
        return surface;
    }

    private static JsonArray intArray(int a, int b) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        return arr;
    }
}
