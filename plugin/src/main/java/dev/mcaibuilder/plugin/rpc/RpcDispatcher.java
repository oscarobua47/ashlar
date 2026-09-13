// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.mcaibuilder.plugin.log.OperationLog;
import dev.mcaibuilder.plugin.net.ClientSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses incoming RPC requests from authenticated connections, dispatches
 * them to a registered {@link RpcHandler}, and writes the response back to
 * the client. Never lets an exception escape and kill the WebSocket thread.
 */
public final class RpcDispatcher {

    private final Map<String, RpcHandler> handlers = new HashMap<>();
    private final OperationLog operationLog;
    private final Logger logger;
    // Response sending and operations.log writes happen here instead of on the
    // main thread (which completes fill_batch/set_blocks futures) or a
    // java-websocket network thread (which completed simple futures like
    // health/auth synchronously before this existed).
    private final ExecutorService responseExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mc-ai-builder-response");
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

    public void register(String method, RpcHandler handler) {
        handlers.put(method, handler);
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
        if (handler == null) {
            sendAndLog(session, RpcResponse.error(id, ErrorCode.UNKNOWN_METHOD, "unknown method: " + method), method);
            return;
        }

        try {
            handler.handle(session, id, request.params()).whenCompleteAsync((result, throwable) -> {
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
     * {@code set_blocks} reports {@code changed}. Anything else (health,
     * auth, an error response) has no such field and logs 0.
     */
    private static long extractBlocksChanged(JsonObject responseJson) {
        if (!responseJson.has("result") || !responseJson.get("result").isJsonObject()) {
            return 0;
        }
        JsonObject result = responseJson.getAsJsonObject("result");
        for (String field : new String[]{"totalChanged", "changed"}) {
            if (result.has(field) && result.get(field).isJsonPrimitive() && result.get(field).getAsJsonPrimitive().isNumber()) {
                return result.get(field).getAsLong();
            }
        }
        return 0;
    }
}
