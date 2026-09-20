// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Argument parsing tests for {@link McRender}, mirroring mc-render.ts's zod schema and body-level checks. */
class McRenderTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void topViewAcceptsTwoElementCorners() {
        McRender.Args a = McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10]}"));
        assertArrayEquals(new int[]{0, 0}, a.from());
        assertArrayEquals(new int[]{10, 10}, a.to());
    }

    @Test
    void facadeViewAcceptsThreeElementCorners() {
        McRender.Args a = McRender.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"view\":\"north\"}"));
        assertArrayEquals(new int[]{0, 60, 0}, a.from());
    }

    @Test
    void missingFromThrows() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(obj("{\"to\":[10,10]}")));
    }

    @Test
    void fromWithOneElementThrows() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(obj("{\"from\":[0],\"to\":[10,10]}")));
    }

    @Test
    void invalidViewThrows() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"view\":\"south-east\"}")));
    }

    @Test
    void sliceRequiresAxisAndAt() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(
                obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"view\":\"slice\",\"slice\":{\"axis\":\"y\"}}")));
    }

    @Test
    void scaleOutOfRangeThrows() {
        ToolArgError e = assertThrows(ToolArgError.class,
                () -> McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"scale\":17}")));
        assertEquals("scale: must be between 0 and 16, got 17", e.getMessage());
    }

    @Test
    void gridOutOfRangeThrows() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"grid\":65}")));
    }

    @Test
    void contourOutOfRangeThrows() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"contour\":4097}")));
    }

    @Test
    void scaleAndGridWithinRangeParse() {
        McRender.Args a = McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"scale\":4,\"grid\":20}"));
        assertEquals(4, a.scale());
        assertEquals(20, a.grid());
    }

    // Body-level checks (mc-render.ts's plain `throw new Error(...)` statements), extracted as static helpers.

    @Test
    void footprintOnlyWithNonTopHeightmapViewThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McRender.checkShapeCompat(true, "north"));
        assertEquals("mc_render view \"north\" needs [x, y, z] corners; only \"top\" and \"heightmap\" accept [x, z].", e.getMessage());
    }

    @Test
    void footprintOnlyWithTopViewDoesNotThrow() {
        assertDoesNotThrow(() -> McRender.checkShapeCompat(true, "top"));
    }

    @Test
    void mismatchedFromToLengthsThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McRender.checkFromToLengthsMatch(true, 2, 3));
        assertEquals("mc_render: from and to must both be [x, z] or both be [x, y, z].", e.getMessage());
    }

    @Test
    void areaOverLimitThrows() {
        assertThrows(ToolArgError.class, () -> McRender.checkArea("top", 200_001));
    }

    @Test
    void volumeOverLimitThrows() {
        assertThrows(ToolArgError.class, () -> McRender.checkVolume(200_001));
    }

    @Test
    void sliceViewWithoutSliceParamThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McRender.checkSliceRequired("slice", null));
        assertEquals("mc_render view \"slice\" requires the \"slice\" parameter: {\"axis\": \"x\"|\"y\"|\"z\", \"at\": <coordinate>}.", e.getMessage());
    }

    @Test
    void nonSliceViewWithoutSliceParamDoesNotThrow() {
        assertDoesNotThrow(() -> McRender.checkSliceRequired("top", null));
    }
}
