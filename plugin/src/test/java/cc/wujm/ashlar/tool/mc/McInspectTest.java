// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Argument parsing tests for {@link McInspect}, mirroring mc-inspect.ts's zod schema. */
class McInspectTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void validNoSliceParses() {
        McInspect.Args a = McInspect.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10]}"));
        assertArrayEquals(new int[]{0, 60, 0}, a.from());
        assertArrayEquals(new int[]{10, 70, 10}, a.to());
        assertNull(a.slice());
    }

    @Test
    void validSliceParses() {
        McInspect.Args a = McInspect.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"slice\":{\"axis\":\"y\",\"at\":65}}"));
        assertEquals("y", a.slice().axis());
        assertEquals(65, a.slice().at());
    }

    @Test
    void missingFromThrows() {
        assertThrows(ToolArgError.class, () -> McInspect.Args.parse(obj("{\"to\":[10,70,10]}")));
    }

    @Test
    void fromWithWrongLengthThrows() {
        assertThrows(ToolArgError.class, () -> McInspect.Args.parse(obj("{\"from\":[0,60],\"to\":[10,70,10]}")));
    }

    @Test
    void invalidSliceAxisThrows() {
        assertThrows(ToolArgError.class,
                () -> McInspect.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"slice\":{\"axis\":\"w\",\"at\":65}}")));
    }

    @Test
    void sliceMissingAtThrows() {
        assertThrows(ToolArgError.class,
                () -> McInspect.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"slice\":{\"axis\":\"y\"}}")));
    }

    @Test
    void volumeWithinLimitDoesNotThrow() {
        McInspect.checkVolume(200_000);
    }

    @Test
    void volumeOverLimitThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McInspect.checkVolume(200_001));
        assertEquals("mc_inspect region volume 200001 exceeds the 200,000-block limit. Reduce the from/to range or split it into several calls.",
                e.getMessage());
    }

    @Test
    void formatDefaultsToStats() {
        McInspect.Args a = McInspect.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10]}"));
        assertEquals("stats", a.format());
    }

    @Test
    void formatColumnsParses() {
        McInspect.Args a = McInspect.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"format\":\"columns\"}"));
        assertEquals("columns", a.format());
    }

    @Test
    void invalidFormatThrows() {
        assertThrows(ToolArgError.class,
                () -> McInspect.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"format\":\"bogus\"}")));
    }

    @Test
    void formatColumnsWithSliceThrowsExclusivityError() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McInspect.Args.parse(
                obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"format\":\"columns\",\"slice\":{\"axis\":\"y\",\"at\":65}}")));
        assertEquals("`slice` and `format: \"columns\"` are exclusive", e.getMessage());
    }

    @Test
    void columnsWithinCapDoesNotThrow() {
        McInspect.checkColumns(1024);
    }

    @Test
    void columnsOverCapThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McInspect.checkColumns(1025));
        assertEquals("mc_inspect format: \"columns\" region has 1025 columns, exceeding the 1024-column limit. Reduce the x/z range.",
                e.getMessage());
    }
}
