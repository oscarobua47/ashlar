// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * A palette + run-length-encoded snapshot of a {@link Region}'s block data
 * (spec &sect;3.4). Traversal order is fixed as {@code y} outer, {@code z}
 * middle, {@code x} inner (plan.md &sect;3.3): {@code for y in minY..maxY: for
 * z in minZ..maxZ: for x in minX..maxX}.
 *
 * <p>This class is deliberately pure Java with no Bukkit imports so it can be
 * unit-tested without a server (plan.md &sect;3.1/&sect;3.3): callers feed it plain
 * {@code minecraft:id[props]} strings (from {@code BlockData#getAsString()})
 * rather than {@code BlockData} instances.
 */
public final class RegionData {

    private final Region region;
    private final List<String> palette;
    private final int[] runIndex;
    private final int[] runLength;

    public RegionData(Region region, List<String> palette, int[] runIndex, int[] runLength) {
        if (runIndex.length != runLength.length) {
            throw new IllegalArgumentException("runIndex and runLength must be the same length");
        }
        this.region = region;
        this.palette = List.copyOf(palette);
        this.runIndex = runIndex.clone();
        this.runLength = runLength.clone();
    }

    public Region region() {
        return region;
    }

    public List<String> palette() {
        return palette;
    }

    /** Palette index for each run, parallel to {@link #runLength()}. Never returns the backing array. */
    public int[] runIndex() {
        return runIndex.clone();
    }

    /** Repeat count for each run (>= 1), parallel to {@link #runIndex()}. */
    public int[] runLength() {
        return runLength.clone();
    }

    public int runCount() {
        return runIndex.length;
    }

    /** Sum of every run's length, computed in {@code long} to avoid overflow. */
    public long volume() {
        long total = 0;
        for (int len : runLength) {
            total += len;
        }
        return total;
    }

    /** Iterates the decoded block string for every cell, in encoding order (y outer, z middle, x inner). */
    public Iterator<String> iterator() {
        return new Iterator<>() {
            private int run = -1;
            private int remainingInRun = 0;

            private void advanceToNonEmptyRun() {
                while (remainingInRun == 0 && run + 1 < runIndex.length) {
                    run++;
                    remainingInRun = runLength[run];
                }
            }

            @Override
            public boolean hasNext() {
                advanceToNonEmptyRun();
                return remainingInRun > 0;
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                remainingInRun--;
                return palette.get(runIndex[run]);
            }
        };
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        JsonObject bounds = new JsonObject();
        bounds.add("from", intArray(region.minX(), region.minY(), region.minZ()));
        bounds.add("to", intArray(region.maxX(), region.maxY(), region.maxZ()));
        json.add("bounds", bounds);
        json.addProperty("order", "y,z,x");
        JsonArray paletteJson = new JsonArray();
        for (String entry : palette) {
            paletteJson.add(entry);
        }
        json.add("palette", paletteJson);
        JsonArray runsJson = new JsonArray();
        for (int i = 0; i < runIndex.length; i++) {
            JsonArray run = new JsonArray();
            run.add(runIndex[i]);
            run.add(runLength[i]);
            runsJson.add(run);
        }
        json.add("runs", runsJson);
        json.addProperty("volume", volume());
        return json;
    }

    public static RegionData fromJson(JsonObject json) {
        JsonObject bounds = json.getAsJsonObject("bounds");
        int[] from = toIntArray(bounds.getAsJsonArray("from"));
        int[] to = toIntArray(bounds.getAsJsonArray("to"));
        Region region = Region.of(from, to);

        List<String> palette = new ArrayList<>();
        for (JsonElement el : json.getAsJsonArray("palette")) {
            palette.add(el.getAsString());
        }

        JsonArray runsJson = json.getAsJsonArray("runs");
        int[] runIndex = new int[runsJson.size()];
        int[] runLength = new int[runsJson.size()];
        for (int i = 0; i < runsJson.size(); i++) {
            JsonArray run = runsJson.get(i).getAsJsonArray();
            runIndex[i] = run.get(0).getAsInt();
            runLength[i] = run.get(1).getAsInt();
        }
        return new RegionData(region, palette, runIndex, runLength);
    }

    private static JsonArray intArray(int a, int b, int c) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        arr.add(c);
        return arr;
    }

    private static int[] toIntArray(JsonArray arr) {
        int[] result = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            result[i] = arr.get(i).getAsInt();
        }
        return result;
    }

    /**
     * Builds a {@link RegionData} by consuming block strings one cell at a
     * time, in encoding order. Assigns palette indices in first-seen order
     * and merges consecutive equal indices into a single run.
     */
    public static final class Encoder {
        private final List<String> palette = new ArrayList<>();
        private final Map<String, Integer> paletteIndex = new HashMap<>();
        private final List<Integer> runIndexList = new ArrayList<>();
        private final List<Integer> runLengthList = new ArrayList<>();
        private int lastIndex = -1;

        public void add(String blockString) {
            int idx = paletteIndex.computeIfAbsent(blockString, s -> {
                palette.add(s);
                return palette.size() - 1;
            });
            if (idx == lastIndex && !runLengthList.isEmpty()) {
                int last = runLengthList.size() - 1;
                runLengthList.set(last, runLengthList.get(last) + 1);
            } else {
                runIndexList.add(idx);
                runLengthList.add(1);
                lastIndex = idx;
            }
        }

        public RegionData finish(Region region) {
            int[] ri = new int[runIndexList.size()];
            int[] rl = new int[runLengthList.size()];
            for (int i = 0; i < ri.length; i++) {
                ri[i] = runIndexList.get(i);
                rl[i] = runLengthList.get(i);
            }
            return new RegionData(region, Collections.unmodifiableList(palette), ri, rl);
        }
    }
}
