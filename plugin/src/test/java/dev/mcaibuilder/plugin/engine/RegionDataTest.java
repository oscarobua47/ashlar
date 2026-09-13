// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.engine;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

/**
 * Unit tests for {@link RegionData}'s palette + RLE encoder/decoder
 * (plan.md &sect;3.3). Pure Java, no Bukkit: exercises the encode/decode round
 * trip and the two shape-specific invariants called out in the plan.
 */
class RegionDataTest {

    private static final Region REGION_2x1x3 = new Region(0, 0, 0, 2, 0, 1); // x:0..2, z:0..1 -> volume 6

    @Test
    void encodeDecodeRoundTripIsConsistent() {
        // y=0,z=0: x=0..2 -> stone,stone,air ; y=0,z=1: x=0..2 -> air,air,glass
        List<String> input = List.of("stone", "stone", "air", "air", "air", "glass");

        RegionData.Encoder encoder = new RegionData.Encoder();
        for (String block : input) {
            encoder.add(block);
        }
        RegionData data = encoder.finish(REGION_2x1x3);

        // Round trip through JSON, the wire format read_region actually returns.
        JsonObject json = data.toJson();
        RegionData decoded = RegionData.fromJson(json);

        assertEquals(REGION_2x1x3, decoded.region());
        assertEquals(input.size(), decoded.volume());

        List<String> decodedBlocks = new ArrayList<>();
        Iterator<String> it = decoded.iterator();
        while (it.hasNext()) {
            decodedBlocks.add(it.next());
        }
        assertIterableEquals(input, decodedBlocks);
    }

    @Test
    void uniformRegionProducesExactlyOneRun() {
        RegionData.Encoder encoder = new RegionData.Encoder();
        long volume = REGION_2x1x3.volume();
        for (long i = 0; i < volume; i++) {
            encoder.add("minecraft:air");
        }
        RegionData data = encoder.finish(REGION_2x1x3);

        assertEquals(1, data.runCount());
        assertEquals(1, data.palette().size());
        assertEquals(volume, data.volume());
        assertEquals(volume, data.runLength()[0]);
    }

    @Test
    void alternatingBlocksProduceOneRunPerCell() {
        RegionData.Encoder encoder = new RegionData.Encoder();
        long volume = REGION_2x1x3.volume();
        for (long i = 0; i < volume; i++) {
            // Alternates every single cell, so no two consecutive cells share an index.
            encoder.add(i % 2 == 0 ? "minecraft:stone" : "minecraft:air");
        }
        RegionData data = encoder.finish(REGION_2x1x3);

        assertEquals(volume, data.runCount());
        assertFalse(data.palette().size() > 2);
        for (int len : data.runLength()) {
            assertEquals(1, len);
        }
    }
}
