// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The OpenAI-compatible chat message shape used throughout the agent (mirrors {@code
 * mcp-server/src/agent/provider.ts}'s {@code ChatMessage}). Content is either a plain string or a
 * list of {@link ContentPart}s (never both); {@code toolCalls}, {@code toolCallId} and {@code
 * name} are optional depending on {@link Role}.
 *
 * <p>Serialisation is hand-written ({@link #toJson()} / {@link #fromJson(JsonObject)}) rather than
 * left to Gson's field reflection, so the wire format sent to the model is stable regardless of
 * internal field names or renames.
 */
public final class ChatMessage {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Role fromWire(String s) {
            for (Role r : values()) {
                if (r.wire().equals(s)) {
                    return r;
                }
            }
            throw new IllegalArgumentException("unknown chat message role: " + s);
        }
    }

    private final Role role;
    private final String contentText;
    private final List<ContentPart> contentParts;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final String name;

    private ChatMessage(Role role, String contentText, List<ContentPart> contentParts, List<ToolCall> toolCalls,
                         String toolCallId, String name) {
        this.role = role;
        this.contentText = contentText;
        this.contentParts = contentParts;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
        this.name = name;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, null, null, null);
    }

    public static ChatMessage userParts(List<ContentPart> parts) {
        return new ChatMessage(Role.USER, null, List.copyOf(parts), null, null, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, null, null, toolCallId, null);
    }

    public static ChatMessage assistantText(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null, null, null, null);
    }

    public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, null, null, List.copyOf(toolCalls), null, null);
    }

    /** Returns a copy of this message with its content-part list replaced (used by history image redaction). */
    public ChatMessage withContentParts(List<ContentPart> newParts) {
        return new ChatMessage(role, null, List.copyOf(newParts), toolCalls, toolCallId, name);
    }

    public Role role() {
        return role;
    }

    /** Non-null only when content is a plain string. */
    public String contentText() {
        return contentText;
    }

    /** Non-null only when content is a list of parts. */
    public List<ContentPart> contentParts() {
        return contentParts;
    }

    /** Non-null and non-empty only for an assistant message carrying tool calls. */
    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String name() {
        return name;
    }

    /** Port of {@code runner.ts}'s {@code textOfContent}: the plain text of this message's content, joined by "\n" for parts. */
    public String textOfContent() {
        if (contentText != null) {
            return contentText;
        }
        if (contentParts != null) {
            List<String> texts = new ArrayList<>();
            for (ContentPart part : contentParts) {
                if (part.isText()) {
                    texts.add(part.text());
                }
            }
            return String.join("\n", texts);
        }
        return "";
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("role", role.wire());
        if (contentParts != null) {
            JsonArray parts = new JsonArray();
            for (ContentPart part : contentParts) {
                parts.add(part.toJson());
            }
            json.add("content", parts);
        } else if (contentText != null) {
            json.addProperty("content", contentText);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            JsonArray calls = new JsonArray();
            for (ToolCall call : toolCalls) {
                calls.add(call.toJson());
            }
            json.add("tool_calls", calls);
        }
        if (toolCallId != null) {
            json.addProperty("tool_call_id", toolCallId);
        }
        if (name != null) {
            json.addProperty("name", name);
        }
        return json;
    }

    public static ChatMessage fromJson(JsonObject json) {
        Role role = Role.fromWire(json.has("role") ? json.get("role").getAsString() : "assistant");
        String contentText = null;
        List<ContentPart> contentParts = null;
        if (json.has("content") && !json.get("content").isJsonNull()) {
            JsonElement content = json.get("content");
            if (content.isJsonArray()) {
                List<ContentPart> parts = new ArrayList<>();
                for (JsonElement e : content.getAsJsonArray()) {
                    parts.add(ContentPart.fromJson(e.getAsJsonObject()));
                }
                contentParts = Collections.unmodifiableList(parts);
            } else {
                contentText = content.getAsString();
            }
        }
        List<ToolCall> toolCalls = null;
        if (json.has("tool_calls") && json.get("tool_calls").isJsonArray()) {
            List<ToolCall> calls = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray("tool_calls")) {
                calls.add(ToolCall.fromJson(e.getAsJsonObject()));
            }
            toolCalls = Collections.unmodifiableList(calls);
        }
        String toolCallId = json.has("tool_call_id") && !json.get("tool_call_id").isJsonNull()
                ? json.get("tool_call_id").getAsString() : null;
        String name = json.has("name") && !json.get("name").isJsonNull() ? json.get("name").getAsString() : null;
        return new ChatMessage(role, contentText, contentParts, toolCalls, toolCallId, name);
    }
}
