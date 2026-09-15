// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool;

import com.google.gson.JsonObject;

/**
 * One MCP result content block (mirrors {@code mcp-server/src/tools/helpers.ts}'s {@code
 * ContentBlock} union). Only the two variants the nine {@code mc_*} tools actually return.
 */
public sealed interface ContentBlock {

    JsonObject toJson();

    record Text(String text) implements ContentBlock {
        @Override
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("type", "text");
            json.addProperty("text", text);
            return json;
        }
    }

    /** {@code data} is base64-encoded image bytes (PNG), never re-encoded here. */
    record Image(String data, String mimeType) implements ContentBlock {
        @Override
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("type", "image");
            json.addProperty("data", data);
            json.addProperty("mimeType", mimeType);
            return json;
        }
    }

    static ContentBlock text(String text) {
        return new Text(text);
    }

    static ContentBlock image(String data, String mimeType) {
        return new Image(data, mimeType);
    }
}
