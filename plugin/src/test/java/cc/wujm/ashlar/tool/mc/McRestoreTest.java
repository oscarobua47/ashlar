// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Argument parsing tests for {@link McRestore}, mirroring mc-restore.ts's zod schema. */
class McRestoreTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void validIdParses() {
        McRestore.Args a = McRestore.Args.parse(obj("{\"id\":\"snap-20260913-220102-a3f9\"}"));
        assertEquals("snap-20260913-220102-a3f9", a.id());
    }

    @Test
    void missingIdThrows() {
        assertThrows(ToolArgError.class, () -> McRestore.Args.parse(obj("{}")));
    }

    @Test
    void emptyIdThrows() {
        assertThrows(ToolArgError.class, () -> McRestore.Args.parse(obj("{\"id\":\"\"}")));
    }
}
