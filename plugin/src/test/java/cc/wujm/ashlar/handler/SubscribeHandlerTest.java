// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.rpc.ErrorCode;
import cc.wujm.ashlar.rpc.RpcError;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SubscribeHandler}'s parameter validation
 * (step6a-prompt.md), exercised directly against the static {@code
 * parseEvents} helper rather than through {@code handle()}: {@code
 * java-websocket} (needed to construct a real {@link
 * cc.wujm.ashlar.net.ClientSession}) is a {@code compileOnly}
 * dependency of the main source set and is not on the test classpath, and
 * this validation never touches the session anyway - it runs entirely on
 * the network thread, before anything is subscribed.
 */
class SubscribeHandlerTest {

    @Test
    void singleKnownEventIsAccepted() {
        JsonObject params = paramsWithEvents("chat");

        Set<String> events = SubscribeHandler.parseEvents(params);

        assertEquals(Set.of("chat"), events);
    }

    @Test
    void unknownEventNameIsRejectedWithBadRequestNamingIt() {
        JsonObject params = paramsWithEvents("nope");

        RpcError error = assertThrows(RpcError.class, () -> SubscribeHandler.parseEvents(params));

        assertEquals(ErrorCode.BAD_REQUEST, error.code());
        assertTrue(error.getMessage().contains("nope"), "error message should name the offending event");
    }

    @Test
    void missingEventsFieldIsRejected() {
        JsonObject params = new JsonObject();

        RpcError error = assertThrows(RpcError.class, () -> SubscribeHandler.parseEvents(params));

        assertEquals(ErrorCode.BAD_REQUEST, error.code());
    }

    @Test
    void eventsMustBeAnArray() {
        JsonObject params = new JsonObject();
        params.addProperty("events", "chat");

        RpcError error = assertThrows(RpcError.class, () -> SubscribeHandler.parseEvents(params));

        assertEquals(ErrorCode.BAD_REQUEST, error.code());
    }

    @Test
    void emptyEventsArrayIsRejected() {
        JsonObject params = new JsonObject();
        params.add("events", new JsonArray());

        RpcError error = assertThrows(RpcError.class, () -> SubscribeHandler.parseEvents(params));

        assertEquals(ErrorCode.BAD_REQUEST, error.code());
    }

    @Test
    void nonStringEventEntryIsRejected() {
        JsonObject params = new JsonObject();
        JsonArray events = new JsonArray();
        events.add(42);
        params.add("events", events);

        RpcError error = assertThrows(RpcError.class, () -> SubscribeHandler.parseEvents(params));

        assertEquals(ErrorCode.BAD_REQUEST, error.code());
    }

    @Test
    void duplicateEventNamesAreDeduplicated() {
        JsonObject params = paramsWithEvents("chat", "chat");

        Set<String> events = SubscribeHandler.parseEvents(params);

        assertEquals(Set.of("chat"), events);
    }

    private static JsonObject paramsWithEvents(String... events) {
        JsonObject params = new JsonObject();
        JsonArray array = new JsonArray();
        for (String event : events) {
            array.add(event);
        }
        params.add("events", array);
        return params;
    }
}
