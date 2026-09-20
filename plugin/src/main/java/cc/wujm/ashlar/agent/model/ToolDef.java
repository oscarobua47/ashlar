// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent.model;

import com.google.gson.JsonObject;
import cc.wujm.ashlar.tool.ToolSpec;

/**
 * The OpenAI {@code tools[].function} shape built from a {@link ToolSpec} (mirrors {@code
 * mcp-server/src/agent/provider.ts}'s {@code ToolDef}).
 */
public record ToolDef(String name, String description, JsonObject parameters) {

    public static ToolDef from(ToolSpec spec) {
        return new ToolDef(spec.name(), spec.description(), spec.inputSchema());
    }

    public JsonObject toJson() {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);
        JsonObject json = new JsonObject();
        json.addProperty("type", "function");
        json.add("function", function);
        return json;
    }
}
