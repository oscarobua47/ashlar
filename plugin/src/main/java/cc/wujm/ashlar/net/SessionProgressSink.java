// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.rpc.InvocationContext;

/**
 * Relays {@code progress} events for one WebSocket-originated RPC invocation
 * back to the client that made the request, built by {@link
 * cc.wujm.ashlar.rpc.RpcDispatcher#dispatch} for every request (plan.md
 * step7). The emitted JSON is byte-identical to what {@code
 * TickBudgetExecutor} sent directly before this class existed: field order
 * {@code event, id, done, total}, with {@code id} keeping the original
 * request id's JSON type (number or string).
 */
public final class SessionProgressSink implements InvocationContext.ProgressSink {

    private final WsServer wsServer;
    private final ClientSession session;
    private final JsonElement requestId;

    public SessionProgressSink(WsServer wsServer, ClientSession session, JsonElement requestId) {
        this.wsServer = wsServer;
        this.session = session;
        this.requestId = requestId;
    }

    @Override
    public void progress(long done, long total) {
        JsonObject event = new JsonObject();
        event.addProperty("event", "progress");
        event.add("id", requestId);
        event.addProperty("done", done);
        event.addProperty("total", total);
        wsServer.sendEvent(session, event);
    }
}
