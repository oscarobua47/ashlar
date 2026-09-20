// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent.model;

import com.google.gson.JsonObject;

/**
 * One tool call requested by the model (mirrors {@code mcp-server/src/agent/provider.ts}'s {@code
 * ToolCall}): an id, and the OpenAI {@code function} shape (name plus a raw JSON-arguments
 * string - not parsed here).
 */
public record ToolCall(String id, String functionName, String arguments) {

    public JsonObject toJson() {
        JsonObject function = new JsonObject();
        function.addProperty("name", functionName);
        function.addProperty("arguments", arguments);
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("type", "function");
        json.add("function", function);
        return json;
    }

    public static ToolCall fromJson(JsonObject json) {
        String id = json.has("id") ? json.get("id").getAsString() : "";
        JsonObject function = json.getAsJsonObject("function");
        String name = function != null && function.has("name") ? function.get("name").getAsString() : "";
        String args = function != null && function.has("arguments") ? function.get("arguments").getAsString() : "";
        return new ToolCall(id, name, args);
    }
}
