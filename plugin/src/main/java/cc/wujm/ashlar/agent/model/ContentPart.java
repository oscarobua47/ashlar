// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent.model;

import com.google.gson.JsonObject;

/**
 * One content part of a {@code user} message: plain text, or an image (mirrors {@code
 * mcp-server/src/agent/provider.ts}'s {@code ContentPart}). {@code detail} is only meaningful for
 * an image part; it is one of "low", "high" or "auto".
 */
public final class ContentPart {

    public enum Type { TEXT, IMAGE_URL }

    private final Type type;
    private final String text;
    private final String url;
    private final String detail;

    private ContentPart(Type type, String text, String url, String detail) {
        this.type = type;
        this.text = text;
        this.url = url;
        this.detail = detail;
    }

    public static ContentPart text(String text) {
        return new ContentPart(Type.TEXT, text, null, null);
    }

    public static ContentPart imageUrl(String url, String detail) {
        return new ContentPart(Type.IMAGE_URL, null, url, detail);
    }

    public Type type() {
        return type;
    }

    public boolean isText() {
        return type == Type.TEXT;
    }

    public boolean isImage() {
        return type == Type.IMAGE_URL;
    }

    public String text() {
        return text;
    }

    public String url() {
        return url;
    }

    public String detail() {
        return detail;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        if (type == Type.TEXT) {
            json.addProperty("type", "text");
            json.addProperty("text", text);
        } else {
            json.addProperty("type", "image_url");
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", url);
            if (detail != null) {
                imageUrl.addProperty("detail", detail);
            }
            json.add("image_url", imageUrl);
        }
        return json;
    }

    public static ContentPart fromJson(JsonObject json) {
        String type = json.has("type") ? json.get("type").getAsString() : "";
        if ("image_url".equals(type)) {
            JsonObject imageUrl = json.getAsJsonObject("image_url");
            String url = imageUrl != null && imageUrl.has("url") ? imageUrl.get("url").getAsString() : "";
            String detail = imageUrl != null && imageUrl.has("detail") && !imageUrl.get("detail").isJsonNull()
                    ? imageUrl.get("detail").getAsString() : null;
            return imageUrl(url, detail);
        }
        String text = json.has("text") && !json.get("text").isJsonNull() ? json.get("text").getAsString() : "";
        return text(text);
    }
}
