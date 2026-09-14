// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.text;

import net.rcwalter.ashlar.engine.RegionData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Pure-Java port of the decoded-region shape {@code mcp-server/src/render/rle.ts}'s {@code
 * decodeRegionData} produces ({@code DecodedRegion}): a randomly-accessible palette-index grid over
 * a bounded region, y-outer/z-middle/x-inner order. No Bukkit dependency.
 *
 * <p>The plugin's own read path ({@link RegionData}) already holds palette + run-length data, so
 * {@link #fromRegionData(RegionData)} builds a {@code BlockGrid} directly from it. {@link
 * #decodeRegionDataJson} additionally mirrors rle.ts's {@code decodeRegionData} bit-for-bit
 * (including its error messages) so the TypeScript test fixtures (rle.test.ts) can be reused as-is
 * for golden tests.
 */
public final class BlockGrid {

    public record Bounds(int[] from, int[] to) {
    }

    public record Size(int dx, int dy, int dz) {
    }

    private final Bounds bounds;
    private final Size size;
    private final List<String> palette;
    private final int[] indices; // y outer, z middle, x inner, length = dx*dy*dz

    private BlockGrid(Bounds bounds, Size size, List<String> palette, int[] indices) {
        this.bounds = bounds;
        this.size = size;
        this.palette = palette;
        this.indices = indices;
    }

    public Bounds bounds() {
        return bounds;
    }

    public Size size() {
        return size;
    }

    public List<String> palette() {
        return palette;
    }

    public int volume() {
        return indices.length;
    }

    /** Block state string at an absolute world coordinate. Throws if outside the decoded bounds. */
    public String at(int x, int y, int z) {
        int minX = bounds.from()[0], minY = bounds.from()[1], minZ = bounds.from()[2];
        int xi = x - minX, yi = y - minY, zi = z - minZ;
        if (xi < 0 || xi >= size.dx() || yi < 0 || yi >= size.dy() || zi < 0 || zi >= size.dz()) {
            throw new IllegalArgumentException("rle: (" + x + "," + y + "," + z + ") is outside the decoded region");
        }
        int flat = (yi * size.dz() + zi) * size.dx() + xi;
        int p = indices[flat];
        if (p < 0 || p >= palette.size()) {
            throw new IllegalArgumentException("rle: palette index out of range at (" + x + "," + y + "," + z + ")");
        }
        return palette.get(p);
    }

    /** Iterates every cell's block state string in encoding order (y outer, z middle, x inner). */
    public Iterator<String> iterator() {
        return new Iterator<>() {
            private int i = 0;

            @Override
            public boolean hasNext() {
                return i < indices.length;
            }

            @Override
            public String next() {
                return palette.get(indices[i++]);
            }
        };
    }

    public List<String> toList() {
        List<String> out = new ArrayList<>(indices.length);
        for (int idx : indices) out.add(palette.get(idx));
        return out;
    }

    /** Builds a {@link BlockGrid} directly from the plugin's own {@link RegionData} (palette + runs). */
    public static BlockGrid fromRegionData(RegionData regionData) {
        int minX = regionData.region().minX(), minY = regionData.region().minY(), minZ = regionData.region().minZ();
        int maxX = regionData.region().maxX(), maxY = regionData.region().maxY(), maxZ = regionData.region().maxZ();
        int dx = maxX - minX + 1, dy = maxY - minY + 1, dz = maxZ - minZ + 1;
        int total = dx * dy * dz;

        int[] runIndex = regionData.runIndex();
        int[] runLength = regionData.runLength();
        int[] indices = new int[total];
        int pos = 0;
        for (int r = 0; r < runIndex.length; r++) {
            int len = runLength[r];
            java.util.Arrays.fill(indices, pos, pos + len, runIndex[r]);
            pos += len;
        }
        if (pos != total) {
            throw new IllegalStateException("rle: decoded " + pos + " cells but region volume is " + total);
        }
        Bounds bounds = new Bounds(new int[]{minX, minY, minZ}, new int[]{maxX, maxY, maxZ});
        return new BlockGrid(bounds, new Size(dx, dy, dz), regionData.palette(), indices);
    }

    /**
     * Byte-for-byte port of rle.ts's {@code decodeRegionData}, taking the same shape (bounds
     * from/to, palette, runs as {@code [paletteIndex, runLength]} pairs, and the region's declared
     * volume) so the TypeScript fixtures decode identically here. Error messages mirror the TS
     * originals (their format, not necessarily their exact wording where JS/Java differ).
     */
    public static BlockGrid decodeRegionDataJson(int[] from, int[] to, List<String> palette, int[][] runs, long declaredVolume) {
        int minX = from[0], minY = from[1], minZ = from[2];
        int maxX = to[0], maxY = to[1], maxZ = to[2];
        int dx = maxX - minX + 1;
        int dy = maxY - minY + 1;
        int dz = maxZ - minZ + 1;
        long total = (long) dx * dy * dz;

        if (dx <= 0 || dy <= 0 || dz <= 0) {
            throw new IllegalArgumentException("rle: invalid bounds");
        }

        int[] indices = new int[(int) total];
        long pos = 0;
        for (int[] run : runs) {
            int idx = run[0];
            int len = run[1];
            if (len < 1) {
                throw new IllegalArgumentException("rle: run length must be >= 1, got " + len);
            }
            if (pos + len > total) {
                throw new IllegalArgumentException("rle: runs overflow region volume " + total + " (at position " + pos + ", run length " + len + ")");
            }
            java.util.Arrays.fill(indices, (int) pos, (int) (pos + len), idx);
            pos += len;
        }
        if (pos != total) {
            throw new IllegalArgumentException("rle: decoded " + pos + " cells but region volume is " + total);
        }

        Bounds bounds = new Bounds(new int[]{minX, minY, minZ}, new int[]{maxX, maxY, maxZ});
        return new BlockGrid(bounds, new Size(dx, dy, dz), List.copyOf(palette), indices);
    }
}
