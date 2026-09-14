// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.rcwalter.ashlar.log.OperationLog;
import net.rcwalter.ashlar.net.ClientSession;
import net.rcwalter.ashlar.net.SessionProgressSink;
import net.rcwalter.ashlar.net.WsServer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses incoming RPC requests from authenticated connections, dispatches
 * them to a registered {@link RpcHandler} (or {@link SessionRpcHandler}),
 * and writes the response back to the client. Never lets an exception escape
 * and kill the WebSocket thread.
 *
 * <p>Builds an {@link InvocationContext} for every request routed to an
 * {@link RpcHandler}: a {@link InvocationContext.Kind#WS_TOKEN} principal
 * carrying the session's remote IP, the request id as the operation id, and
 * a {@link SessionProgressSink} wired to {@link #wsServer} so {@code
 * progress} events reach the client exactly as before this class existed
 * (plan.md step7).
 */
public final class RpcDispatcher {

    private final Map<String, RpcHandler> handlers = new HashMap<>();
    private final Map<String, SessionRpcHandler> sessionHandlers = new HashMap<>();
    private final OperationLog operationLog;
    private final Logger logger;
    private volatile WsServer wsServer;
    // Response sending and operations.log writes happen here instead of on the
    // main thread (which completes fill_batch/set_blocks futures) or a
    // java-websocket network thread (which completed simple futures like
    // health/auth synchronously before this existed).
    private final ExecutorService responseExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ashlar-response");
        t.setDaemon(true);
        return t;
    });

    public RpcDispatcher(OperationLog operationLog, Logger logger) {
        this.operationLog = operationLog;
        this.logger = logger;
    }

    /** Shuts down the response executor. Call from {@code onDisable}. */
    public void shutdown() {
        responseExecutor.shutdown();
    }

    /** Wired in after the {@link WsServer} exists (construction-order workaround), so progress events can be sent. */
    public void setWsServer(WsServer wsServer) {
        this.wsServer = wsServer;
    }

    public void register(String method, RpcHandler handler) {
        handlers.put(method, handler);
    }

    /** Registers a transport-level exception handler that needs the raw {@link ClientSession}; see {@link SessionRpcHandler}. */
    public void register(String method, SessionRpcHandler handler) {
        sessionHandlers.put(method, handler);
    }

    public void dispatch(ClientSession session, String rawMessage) {
        JsonObject requestObject;
        try {
            JsonElement parsed = JsonParser.parseString(rawMessage);
            if (!parsed.isJsonObject()) {
                throw new JsonSyntaxException("request must be a JSON object");
            }
            requestObject = parsed.getAsJsonObject();
        } catch (Exception e) {
            // Malformed JSON: reply BAD_REQUEST with id:null, connection stays open.
            sendAndLog(session, RpcResponse.error(JsonNull.INSTANCE, ErrorCode.BAD_REQUEST, "malformed JSON"), "unknown");
            return;
        }

        JsonElement id = (requestObject.has("id") && !requestObject.get("id").isJsonNull())
                ? requestObject.get("id")
                : JsonNull.INSTANCE;

        if (!requestObject.has("method") || !requestObject.get("method").isJsonPrimitive()) {
            sendAndLog(session, RpcResponse.error(id, ErrorCode.BAD_REQUEST, "missing \"method\""), "unknown");
            return;
        }
        String method = requestObject.get("method").getAsString();

        JsonObject params = (requestObject.has("params") && requestObject.get("params").isJsonObject())
                ? requestObject.getAsJsonObject("params")
                : new JsonObject();

        RpcRequest request = new RpcRequest(id, method, params);
        RpcHandler handler = handlers.get(request.method());
        SessionRpcHandler sessionHandler = (handler == null) ? sessionHandlers.get(request.method()) : null;
        if (handler == null && sessionHandler == null) {
            sendAndLog(session, RpcResponse.error(id, ErrorCode.UNKNOWN_METHOD, "unknown method: " + method), method);
            return;
        }

        try {
            CompletableFuture<JsonElement> future = (handler != null)
                    ? handler.handle(buildContext(session, id), request.params())
                    : sessionHandler.handle(session, id, request.params());
            future.whenCompleteAsync((result, throwable) -> {
                if (throwable != null) {
                    Throwable cause = (throwable instanceof CompletionException && throwable.getCause() != null)
                            ? throwable.getCause()
                            : throwable;
                    if (cause instanceof RpcError rpcError) {
                        sendAndLog(session, RpcResponse.error(id, rpcError.code(), rpcError.getMessage()), method);
                    } else {
                        logger.log(Level.SEVERE, "Unhandled exception while executing method '" + method + "'", cause);
                        sendAndLog(session, RpcResponse.error(id, ErrorCode.INTERNAL, cause.getClass().getName()), method);
                    }
                } else {
                    sendAndLog(session, RpcResponse.ok(id, result), method);
                }
            }, responseExecutor);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Handler for method '" + method + "' threw synchronously", e);
            sendAndLog(session, RpcResponse.error(id, ErrorCode.INTERNAL, e.getClass().getName()), method);
        }
    }

    /**
     * Builds the {@link InvocationContext} for one WebSocket-originated
     * request: a {@link InvocationContext.Kind#WS_TOKEN} principal (the
     * session's remote IP, used as both id and display), the request id as
     * the operation id, and a {@link SessionProgressSink} that relays {@code
     * progress} events to this session through {@link #wsServer}. Falls back
     * to the default NOOP sink if {@link #wsServer} has not been wired in
     * yet (should not happen once {@code onEnable} finishes).
     */
    private InvocationContext buildContext(ClientSession session, JsonElement id) {
        WsServer server = wsServer;
        InvocationContext.ProgressSink sink = (server != null) ? new SessionProgressSink(server, session, id) : null;
        InvocationContext.Principal principal =
                new InvocationContext.Principal(InvocationContext.Kind.WS_TOKEN, session.getRemoteIp(), session.getRemoteIp());
        return InvocationContext.of(principal, operationIdOf(id), sink);
    }

    /** The request id as a string: its own text for a string id, its JSON text otherwise (a number, or {@code "null"}). */
    private static String operationIdOf(JsonElement id) {
        if (id == null || id.isJsonNull()) {
            return "null";
        }
        if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
            return id.getAsString();
        }
        return id.toString();
    }

    private void sendAndLog(ClientSession session, RpcResponse response, String method) {
        JsonObject json = response.toJson();
        try {
            session.getConnection().send(json.toString());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to send response to " + session.getRemoteIp(), e);
        }
        boolean ok = json.has("ok") && json.get("ok").getAsBoolean();
        operationLog.append(session.getRemoteIp(), method, ok ? "ok" : "error", extractBlocksChanged(json));
    }

    /**
     * Reads how many blocks a completed operation changed from its result
     * object, if any: {@code fill_batch} reports {@code totalChanged},
     * {@code set_blocks} reports {@code changed}, {@code restore} reports
     * {@code restored}. Read-only methods (health, auth, heightmap,
     * read_region, snapshot, list_snapshots, run_command) and error
     * responses have none of these fields and log 0.
     */
    private static long extractBlocksChanged(JsonObject responseJson) {
        if (!responseJson.has("result") || !responseJson.get("result").isJsonObject()) {
            return 0;
        }
        JsonObject result = responseJson.getAsJsonObject("result");
        for (String field : new String[]{"totalChanged", "changed", "restored"}) {
            if (result.has(field) && result.get(field).isJsonPrimitive() && result.get(field).getAsJsonPrimitive().isNumber()) {
                return result.get(field).getAsLong();
            }
        }
        return 0;
    }
}
