// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.net;

import org.java_websocket.WebSocket;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Per-connection state: the underlying WebSocket, whether it has completed
 * the auth handshake, the remote IP it connected from, when it connected,
 * and which server-initiated events (step6a-prompt.md: {@code subscribe})
 * it has opted in to.
 */
public final class ClientSession {

    private final WebSocket connection;
    private final String remoteIp;
    private final Instant connectedAt = Instant.now();
    private volatile boolean authenticated = false;
    private volatile ScheduledFuture<?> authTimeoutTask;
    private final Set<String> subscribedEvents = ConcurrentHashMap.newKeySet();

    public ClientSession(WebSocket connection, String remoteIp) {
        this.connection = connection;
        this.remoteIp = remoteIp;
    }

    public WebSocket getConnection() {
        return connection;
    }

    public String getRemoteIp() {
        return remoteIp;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public void setAuthTimeoutTask(ScheduledFuture<?> task) {
        this.authTimeoutTask = task;
    }

    /** Cancels the pending 5s auth timeout, if any. Safe to call multiple times. */
    public void cancelAuthTimeout() {
        ScheduledFuture<?> task = authTimeoutTask;
        if (task != null) {
            task.cancel(false);
        }
    }

    /** Opts this connection in to a server-initiated event. Idempotent. */
    public void subscribe(String eventName) {
        subscribedEvents.add(eventName);
    }

    public boolean isSubscribed(String eventName) {
        return subscribedEvents.contains(eventName);
    }
}
