// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.rcwalter.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argument parsing tests for {@link McBuild}, mirroring mc-build.ts's zod schema and refine rule. */
class McBuildTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void fillsOnlyParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"fills\":[{\"from\":[0,60,0],\"to\":[10,70,10],\"block\":\"minecraft:stone\"}]}"));
        assertEquals(1, a.fills().size());
        assertEquals(0, a.blocks().size());
        assertFalse(a.snapshot());
        assertNull(a.connect());
    }

    @Test
    void blocksOnlyParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:torch\"}]}"));
        assertEquals(1, a.blocks().size());
        assertEquals(0, a.fills().size());
    }

    @Test
    void emptyBodyThrowsRefineMessage() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj("{}")));
        assertEquals("\"fills\" and/or \"blocks\" must be provided, with at least one non-empty", e.getMessage());
    }

    @Test
    void emptyFillsAndBlocksArraysThrowRefineMessage() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj("{\"fills\":[],\"blocks\":[]}")));
    }

    @Test
    void fillWithEmptyBlockThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(
                obj("{\"fills\":[{\"from\":[0,60,0],\"to\":[10,70,10],\"block\":\"\"}]}")));
    }

    @Test
    void fillWithInvalidModeThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(
                obj("{\"fills\":[{\"from\":[0,60,0],\"to\":[10,70,10],\"block\":\"minecraft:stone\",\"mode\":\"invert\"}]}")));
    }

    @Test
    void fillWithValidModeAndFilterParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"fills\":[{\"from\":[0,60,0],\"to\":[10,70,10],\"block\":\"minecraft:stone\",\"mode\":\"hollow\",\"filter\":\"minecraft:air\"}]}"));
        McBuild.FillOpArg f = a.fills().get(0);
        assertEquals("hollow", f.mode());
        assertEquals("minecraft:air", f.filter());
    }

    @Test
    void blockMissingPosThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj("{\"blocks\":[{\"block\":\"minecraft:torch\"}]}")));
    }

    @Test
    void snapshotAndConnectFlagsParse() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:torch\"}],\"snapshot\":true,\"connect\":false}"));
        assertTrue(a.snapshot());
        assertFalse(a.connect());
    }

    @Test
    void liquidsOmittedParsesAsNull() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"fills\":[{\"from\":[0,60,0],\"to\":[10,70,10],\"block\":\"minecraft:stone\"}]}"));
        assertNull(a.liquids());
    }

    @Test
    void liquidsStaticParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:water\"}],\"liquids\":\"static\"}"));
        assertEquals("static", a.liquids());
    }

    @Test
    void liquidsFlowParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:water\"}],\"liquids\":\"flow\"}"));
        assertEquals("flow", a.liquids());
    }

    @Test
    void liquidsInvalidValueThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:water\"}],\"liquids\":\"gushing\"}")));
    }

    @Test
    void signWithFrontAndBackParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:oak_sign\",\"sign\":{\"front\":[\"Hello\"],\"back\":[\"World\"],\"color\":\"red\",\"glowing\":true}}]}"));
        McBuild.SparseOpArg b = a.blocks().get(0);
        assertEquals("Hello", b.sign().front().get(0));
        assertEquals("World", b.sign().back().get(0));
        assertEquals("red", b.sign().color());
        assertTrue(b.sign().glowing());
    }

    @Test
    void signWithFiveLinesThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:oak_sign\","
                        + "\"sign\":{\"front\":[\"1\",\"2\",\"3\",\"4\",\"5\"]}}]}")));
    }

    @Test
    void signLineOver64CharsThrows() {
        String longLine = "x".repeat(65);
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:oak_sign\",\"sign\":{\"front\":[\"" + longLine + "\"]}}]}")));
    }
}
