// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.rcwalter.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argument parsing tests for {@link McSurvey}, mirroring mc-survey.ts's zod schema. */
class McSurveyTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void minimalArgsParseWithNullDefaults() {
        McSurvey.Args a = McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10]}"));
        assertArrayEquals(new int[]{0, 0}, a.from());
        assertArrayEquals(new int[]{10, 10}, a.to());
        assertNull(a.type());
        assertNull(a.format());
        assertNull(a.matrix());
    }

    @Test
    void fullArgsParse() {
        McSurvey.Args a = McSurvey.Args.parse(
                obj("{\"world\":\"world\",\"from\":[0,0],\"to\":[10,10],\"type\":\"SOLID\",\"format\":\"text\",\"matrix\":true}"));
        assertEquals("world", a.world());
        assertEquals("SOLID", a.type());
        assertEquals("text", a.format());
        assertTrue(a.matrix());
    }

    @Test
    void missingFromThrows() {
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"to\":[10,10]}")));
    }

    @Test
    void fromWithThreeElementsThrows() {
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0,0],\"to\":[10,10]}")));
    }

    @Test
    void invalidTypeThrows() {
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"type\":\"LAVA\"}")));
    }

    @Test
    void invalidFormatThrows() {
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"format\":\"json\"}")));
    }

    @Test
    void areaWithinLimitDoesNotThrow() {
        McSurvey.checkArea(200_000);
    }

    @Test
    void areaOverLimitThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McSurvey.checkArea(200_001));
        assertEquals("mc_survey area 200001 exceeds the 200,000-cell limit. Reduce the from/to range or split it into several calls.", e.getMessage());
    }
}
