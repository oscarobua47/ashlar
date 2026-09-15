// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * The result of one {@code tool_call} (mirrors {@code mcp-server/src/tools/helpers.ts}'s {@code
 * ToolContentResult}): a list of content blocks plus whether this is an error result.
 */
public record ToolResult(List<ContentBlock> content, boolean isError) {

    public static ToolResult text(String text) {
        return new ToolResult(List.of(ContentBlock.text(text)), false);
    }

    public static ToolResult content(List<ContentBlock> content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String text) {
        return new ToolResult(List.of(ContentBlock.text(text)), true);
    }

    public JsonObject toJson() {
        JsonArray contentJson = new JsonArray();
        for (ContentBlock block : content) {
            contentJson.add(block.toJson());
        }
        JsonObject json = new JsonObject();
        json.add("content", contentJson);
        json.addProperty("isError", isError);
        return json;
    }
}
