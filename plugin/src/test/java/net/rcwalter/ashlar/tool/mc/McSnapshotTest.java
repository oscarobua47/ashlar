// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.rcwalter.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Argument parsing tests for {@link McSnapshot}, mirroring mc-snapshot.ts's zod schema and refine rule. */
class McSnapshotTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void defaultActionIsCreate() {
        McSnapshot.Args a = McSnapshot.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10]}"));
        assertEquals("create", a.action());
        assertArrayEquals(new int[]{0, 60, 0}, a.from());
        assertArrayEquals(new int[]{10, 70, 10}, a.to());
    }

    @Test
    void listActionNeedsNoFromTo() {
        McSnapshot.Args a = McSnapshot.Args.parse(obj("{\"action\":\"list\"}"));
        assertEquals("list", a.action());
    }

    @Test
    void createWithoutFromThrowsRefineMessage() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McSnapshot.Args.parse(obj("{\"to\":[10,70,10]}")));
        assertEquals("\"from\" and \"to\" are required when action is \"create\"", e.getMessage());
    }

    @Test
    void createWithoutToThrowsRefineMessage() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McSnapshot.Args.parse(obj("{\"from\":[0,60,0]}")));
        assertEquals("\"from\" and \"to\" are required when action is \"create\"", e.getMessage());
    }

    @Test
    void explicitCreateActionAlsoRequiresFromTo() {
        assertThrows(ToolArgError.class, () -> McSnapshot.Args.parse(obj("{\"action\":\"create\"}")));
    }

    @Test
    void invalidActionThrows() {
        assertThrows(ToolArgError.class, () -> McSnapshot.Args.parse(obj("{\"action\":\"delete\"}")));
    }

    @Test
    void fromWithWrongLengthThrows() {
        assertThrows(ToolArgError.class, () -> McSnapshot.Args.parse(obj("{\"from\":[0,60],\"to\":[10,70,10]}")));
    }

    @Test
    void labelAndWorldAreOptional() {
        McSnapshot.Args a = McSnapshot.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"world\":\"world_nether\",\"label\":\"before tower\"}"));
        assertEquals("world_nether", a.world());
        assertEquals("before tower", a.label());
    }

    @Test
    void volumeWithinLimitDoesNotThrow() {
        McSnapshot.checkVolume(200_000);
    }

    @Test
    void volumeOverLimitThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McSnapshot.checkVolume(200_001));
        assertEquals("mc_snapshot region volume 200001 exceeds the 200,000-block limit. Reduce the from/to range or split it into several snapshots.",
                e.getMessage());
    }
}
