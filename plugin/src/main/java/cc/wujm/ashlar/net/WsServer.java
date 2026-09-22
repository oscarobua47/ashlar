// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.rpc.ErrorCode;
import cc.wujm.ashlar.rpc.RpcDispatcher;
import cc.wujm.ashlar.rpc.RpcResponse;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The WebSocket endpoint. Runs entirely on java-websocket's own network
 * threads; nothing here may touch Bukkit/Paper API directly (see
 * {@link cc.wujm.ashlar.rpc.MainThread}).
 *
 * <p>Auth handshake is handled directly here (it is not an RPC method with
 * side effects on the game); everything else is handed off to
 * {@link RpcDispatcher} once a connection is authenticated.
 */
public final class WsServer extends WebSocketServer {

    private final ConfigHolder configHolder;
    // server.token is cold (step8j-prompt.md): frozen at construction rather than read through
    // configHolder, so a reloaded token never takes effect until a restart, even though
    // server.allowed-ips below (hot) is checked fresh on every connection.
    private final String token;
    private final RpcDispatcher dispatcher;
    private final Logger logger;
    private final Map<WebSocket, ClientSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService authTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ashlar-auth-timer");
        t.setDaemon(true);
        return t;
    });

    public WsServer(InetSocketAddress address, ConfigHolder configHolder, RpcDispatcher dispatcher, Logger logger) {
        super(address);
        this.configHolder = configHolder;
        this.token = configHolder.get().server().token();
        this.dispatcher = dispatcher;
        this.logger = logger;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String ip = remoteIp(conn);
        if (!configHolder.get().isIpAllowed(ip)) {
            logger.info("Rejected connection from " + ip + ": not in allowed-ips");
            conn.close(4003, "ip not allowed");
            return;
        }

        ClientSession session = new ClientSession(conn, ip);
        sessions.put(conn, session);

        ScheduledFuture<?> timeoutTask = authTimer.schedule(() -> {
            if (!session.isAuthenticated()) {
                logger.info("Closing connection from " + ip + ": auth timeout");
                conn.close(4001, "auth timeout");
            }
        }, 5, TimeUnit.SECONDS);
        session.setAuthTimeoutTask(timeoutTask);

        logger.info("Connection opened from " + ip);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        ClientSession session = sessions.get(conn);
        if (session == null) {
            // Should not happen: onOpen always creates a session before any
            // message can arrive. Close defensively rather than throw.
            conn.close(4000, "no session");
            return;
        }

        if (!session.isAuthenticated()) {
            handleAuth(conn, session, message);
            return;
        }

        dispatcher.dispatch(session, message);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        ClientSession session = sessions.remove(conn);
        String ip = session != null ? session.getRemoteIp() : remoteIp(conn);
        if (session != null) {
            session.cancelAuthTimeout();
        }
        logger.info("Connection closed: ip=" + ip + " code=" + code + " reason=" + reason + " remote=" + remote);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        String ip = conn != null ? remoteIp(conn) : "unknown";
        logger.info("WebSocket error: ip=" + ip + " message=" + ex.getMessage());
        // Must never throw from here; java-websocket calls this from network threads.
    }

    @Override
    public void onStart() {
        logger.info("WebSocket server bound to " + getAddress());
    }

    /** Sends a server-initiated event (e.g. progress) to an authenticated client. */
    public void sendEvent(ClientSession session, JsonObject event) {
        try {
            session.getConnection().send(event.toString());
        } catch (Exception e) {
            logger.warning("Failed to send event to " + session.getRemoteIp() + ": " + e.getMessage());
        }
    }

    /**
     * Sends {@code event} to every authenticated session subscribed to
     * {@code eventName} (step6a-prompt.md: {@code /ashlar} pushing a
     * {@code chat}/{@code chat_cancel} event to a connected agent process).
     * Never throws; a failed send to one session is logged and does not stop
     * delivery to the others. Returns how many sessions it sent to.
     */
    public int broadcastEvent(String eventName, JsonObject event) {
        String payload = event.toString();
        int sent = 0;
        for (ClientSession session : sessions.values()) {
            if (!session.isAuthenticated() || !session.isSubscribed(eventName)) {
                continue;
            }
            try {
                session.getConnection().send(payload);
                sent++;
            } catch (Exception e) {
                logger.warning("Failed to broadcast event '" + eventName + "' to " + session.getRemoteIp() + ": " + e.getMessage());
            }
        }
        return sent;
    }

    /** Whether any authenticated session is currently subscribed to {@code eventName}. */
    public boolean hasSubscriber(String eventName) {
        for (ClientSession session : sessions.values()) {
            if (session.isAuthenticated() && session.isSubscribed(eventName)) {
                return true;
            }
        }
        return false;
    }

    /** Stops the server with a 1s timeout and shuts down the auth-timer executor. */
    public void shutdown() {
        try {
            this.stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            authTimer.shutdownNow();
        }
    }

    private void handleAuth(WebSocket conn, ClientSession session, String message) {
        JsonObject obj;
        try {
            JsonElement parsed = JsonParser.parseString(message);
            if (!parsed.isJsonObject()) {
                throw new JsonSyntaxException("not an object");
            }
            obj = parsed.getAsJsonObject();
        } catch (Exception e) {
            logger.info("Closing connection from " + session.getRemoteIp() + ": first message was not valid JSON");
            conn.close(4001, "authentication required");
            return;
        }

        JsonElement id = (obj.has("id") && !obj.get("id").isJsonNull()) ? obj.get("id") : JsonNull.INSTANCE;
        String method = (obj.has("method") && obj.get("method").isJsonPrimitive()) ? obj.get("method").getAsString() : null;
        if (!"auth".equals(method)) {
            logger.info("Closing connection from " + session.getRemoteIp() + ": first message was not auth");
            conn.close(4001, "authentication required");
            return;
        }

        JsonObject params = (obj.has("params") && obj.get("params").isJsonObject()) ? obj.getAsJsonObject("params") : new JsonObject();
        String token = (params.has("token") && params.get("token").isJsonPrimitive()) ? params.get("token").getAsString() : "";

        boolean valid = MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                this.token.getBytes(StandardCharsets.UTF_8));

        if (!valid) {
            conn.send(RpcResponse.error(id, ErrorCode.UNAUTHORIZED, "invalid token").toJson().toString());
            logger.info("Closing connection from " + session.getRemoteIp() + ": invalid token");
            conn.close(4001, "unauthorized");
            return;
        }

        session.cancelAuthTimeout();
        session.setAuthenticated(true);
        JsonObject result = new JsonObject();
        result.addProperty("authenticated", true);
        conn.send(RpcResponse.ok(id, result).toJson().toString());
        logger.info("Client authenticated: ip=" + session.getRemoteIp());
    }

    private static String remoteIp(WebSocket conn) {
        InetSocketAddress addr = conn.getRemoteSocketAddress();
        return (addr != null && addr.getAddress() != null) ? addr.getAddress().getHostAddress() : "unknown";
    }
}
