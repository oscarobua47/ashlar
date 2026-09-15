// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.rcwalter.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Argument parsing tests for {@link McCommand}, mirroring mc-command.ts's zod schema. */
class McCommandTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void validCommandParses() {
        McCommand.Args a = McCommand.Args.parse(obj("{\"command\":\"weather clear\"}"));
        assertEquals("weather clear", a.command());
    }

    @Test
    void missingCommandThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{}")));
        assertEquals("command: required, must be a string", e.getMessage());
    }

    @Test
    void emptyCommandThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{\"command\":\"\"}")));
        assertEquals("command: must contain at least 1 character(s)", e.getMessage());
    }

    @Test
    void nonStringCommandThrows() {
        assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{\"command\":123}")));
    }
}
