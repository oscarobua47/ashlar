// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.net.ClientSession;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code subscribe}: opts the calling connection in to server-initiated
 * events, so a connected agent process can receive {@code chat}/{@code
 * chat_cancel} events pushed by the in-game {@code /ashlar} command
 * (step6a-prompt.md). Params: {@code {"events": ["chat"]}}. The only known
 * event name today is {@code "chat"}; any other name is rejected with
 * BAD_REQUEST naming it, and nothing is subscribed. Re-subscribing to an
 * already-subscribed event is idempotent. Runs entirely on the network
 * thread: no Bukkit access.
 */
public final class SubscribeHandler implements RpcHandler {

    private static final Set<String> KNOWN_EVENTS = Set.of("chat");

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        Set<String> events;
        try {
            events = parseEvents(params);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }

        for (String event : events) {
            session.subscribe(event);
        }

        JsonArray subscribed = new JsonArray();
        for (String event : events) {
            subscribed.add(event);
        }
        JsonObject result = new JsonObject();
        result.add("subscribed", subscribed);
        return CompletableFuture.completedFuture(result);
    }

    /** Validates {@code params.events} and returns the requested event names, deduplicated but order-preserved. */
    static Set<String> parseEvents(JsonObject params) {
        if (!params.has("events") || !params.get("events").isJsonArray()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"events\" must be an array of strings");
        }
        JsonArray array = params.getAsJsonArray("events");
        if (array.isEmpty()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"events\" must not be empty");
        }
        Set<String> events = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new RpcError(ErrorCode.BAD_REQUEST, "\"events\" must be an array of strings");
            }
            String event = element.getAsString();
            if (!KNOWN_EVENTS.contains(event)) {
                throw new RpcError(ErrorCode.BAD_REQUEST, "unknown event: '" + event + "'");
            }
            events.add(event);
        }
        return events;
    }
}
