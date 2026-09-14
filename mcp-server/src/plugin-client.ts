// SPDX-License-Identifier: AGPL-3.0-or-later

import { PluginError } from "./errors.js";

/**
 * A long-lived WebSocket connection to a single Paper plugin instance
 * (spec section 3.2). This class is meant to be constructed exactly once
 * per MCP server process and shared, module-scoped, by every per-request
 * `McpServer` the HTTP/stdio factories create (plan section 4.1: "the
 * plugin WebSocket client is one module-scoped singleton"). Never construct
 * one per request.
 *
 * Responsibilities:
 * - Performs the `auth` handshake on every connect (spec section 3.2).
 * - Reconnects automatically with exponential backoff (1s doubling to 30s)
 *   whenever the socket closes, until {@link close} is called.
 * - Matches request/response pairs by `id` and resolves/rejects the
 *   caller's promise; rejects with {@link PluginError} on `{"ok":false}`.
 * - Requests issued while disconnected wait up to `connectWaitMs`
 *   (default 10s) for a connection before failing.
 * - Logs `progress` events to stderr, throttled to at most one line every
 *   2 seconds per request id (plan section 4.1).
 */
export class PluginClient {
    private readonly url: string;
    private readonly token: string;
    private readonly defaultTimeoutMs: number;
    private readonly connectWaitMs: number;
    private readonly subscribeEvents: string[];

    // Included in every log line below so the two stdio instances a client
    // like Claude Desktop can start per configured server are distinguishable.
    private readonly logTag = `[plugin-client ${process.pid}]`;

    private ws: WebSocket | null = null;
    private authenticated = false;
    private closed = false;
    private reconnectDelayMs = 1000;
    private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    private nextId = 1;
    private nextSubId = 1;

    private readonly pending = new Map<
        string,
        { resolve: (v: unknown) => void; reject: (e: Error) => void; timeoutHandle: ReturnType<typeof setTimeout> }
    >();
    private connectWaiters: Array<() => void> = [];
    private readonly lastProgressLogAt = new Map<string, number>();
    private readonly eventHandlers: Array<(event: Record<string, unknown>) => void> = [];

    constructor(opts: {
        url: string;
        token: string;
        defaultTimeoutMs: number;
        connectWaitMs?: number;
        subscribeEvents?: string[];
    }) {
        this.url = opts.url;
        this.token = opts.token;
        this.defaultTimeoutMs = opts.defaultTimeoutMs;
        this.connectWaitMs = opts.connectWaitMs ?? 10_000;
        this.subscribeEvents = opts.subscribeEvents ?? [];
    }

    /**
     * Registers a handler invoked for every server-initiated event message
     * (any message with a string `event` field other than `"progress"`),
     * e.g. the `chat` and `chat_cancel` events the `/ashlar` command pushes.
     * Handler exceptions are caught and logged; they never affect the
     * connection or other handlers.
     */
    onEvent(handler: (event: Record<string, unknown>) => void): void {
        this.eventHandlers.push(handler);
    }

    /** Starts the connection loop. Call once at process startup. */
    start(): void {
        this.connect();
    }

    /** Whether the plugin connection is currently authenticated and usable. */
    isConnected(): boolean {
        return this.authenticated && this.ws !== null;
    }

    /** The plugin's WebSocket URL, for display in error messages. */
    get pluginUrl(): string {
        return this.url;
    }

    /** Closes the connection permanently; no further reconnect attempts are made. */
    close(): void {
        this.closed = true;
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        if (this.ws) {
            try {
                this.ws.close();
            } catch {
                // ignore
            }
            this.ws = null;
        }
        const shutdownError = new PluginError("UNAVAILABLE", "the MCP server is shutting down");
        for (const [id, entry] of this.pending) {
            clearTimeout(entry.timeoutHandle);
            entry.reject(shutdownError);
            this.pending.delete(id);
        }
    }

    /**
     * Sends `method`/`params` to the plugin and resolves with `result` once
     * the matching `{"id":..., "ok":true, "result":...}` response arrives.
     * Rejects with {@link PluginError} on `{"ok":false}`, on a timeout, or
     * when no connection becomes available within `connectWaitMs`.
     */
    async request(method: string, params: unknown, timeoutMs = this.defaultTimeoutMs): Promise<unknown> {
        await this.waitForConnection();

        const id = `req-${this.nextId++}`;
        return new Promise<unknown>((resolve, reject) => {
            const timeoutHandle = setTimeout(() => {
                this.pending.delete(id);
                reject(new PluginError("TIMEOUT", `plugin did not respond to "${method}" within ${timeoutMs}ms`));
            }, timeoutMs);
            this.pending.set(id, { resolve, reject, timeoutHandle });
            this.sendRaw({ id, method, params });
        });
    }

    private waitForConnection(): Promise<void> {
        if (this.isConnected()) {
            return Promise.resolve();
        }
        return new Promise<void>((resolve, reject) => {
            const timer = setTimeout(() => {
                const idx = this.connectWaiters.indexOf(onConnected);
                if (idx >= 0) this.connectWaiters.splice(idx, 1);
                reject(
                    new PluginError(
                        "UNAVAILABLE",
                        `cannot reach the plugin at ${this.url}: no connection established within ${this.connectWaitMs}ms`
                    )
                );
            }, this.connectWaitMs);
            const onConnected = () => {
                clearTimeout(timer);
                resolve();
            };
            this.connectWaiters.push(onConnected);
        });
    }

    private flushConnectWaiters(): void {
        const waiters = this.connectWaiters;
        this.connectWaiters = [];
        for (const w of waiters) w();
    }

    private connect(): void {
        if (this.closed) return;
        console.error(`${this.logTag} connecting to ${this.url}`);
        let ws: WebSocket;
        try {
            ws = new WebSocket(this.url);
        } catch (err) {
            console.error(`${this.logTag} failed to open connection: ${(err as Error).message}`);
            this.scheduleReconnect();
            return;
        }
        this.ws = ws;

        ws.addEventListener("open", () => {
            console.error(`${this.logTag} connected, authenticating`);
            this.sendRaw({ id: "auth-1", method: "auth", params: { token: this.token } });
        });

        ws.addEventListener("message", ev => {
            this.onMessage(String(ev.data));
        });

        ws.addEventListener("close", ev => {
            console.error(`${this.logTag} disconnected (code=${ev.code}${ev.reason ? `, reason=${ev.reason}` : ""})`);
            this.handleDisconnect();
        });

        ws.addEventListener("error", () => {
            // The "close" event always follows; nothing extra to do here.
        });
    }

    private handleDisconnect(): void {
        this.ws = null;
        this.authenticated = false;
        this.scheduleReconnect();
    }

    private scheduleReconnect(): void {
        if (this.closed || this.reconnectTimer) return;
        const delay = this.reconnectDelayMs;
        this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            this.connect();
        }, delay);
        this.reconnectDelayMs = Math.min(this.reconnectDelayMs * 2, 30_000);
    }

    private sendRaw(obj: unknown): void {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
        this.ws.send(JSON.stringify(obj));
    }

    private onMessage(raw: string): void {
        let msg: Record<string, unknown>;
        try {
            msg = JSON.parse(raw) as Record<string, unknown>;
        } catch {
            console.error(`${this.logTag} received non-JSON message, ignoring`);
            return;
        }

        if (msg.event === "progress") {
            this.handleProgress(msg);
            return;
        }

        if (msg.id === "auth-1") {
            if (msg.ok) {
                this.authenticated = true;
                this.reconnectDelayMs = 1000;
                console.error(`${this.logTag} authenticated`);
                this.sendSubscribe();
                this.flushConnectWaiters();
            } else {
                const error = msg.error as { code?: string; message?: string } | undefined;
                console.error(
                    `${this.logTag} authentication failed: ${error?.code ?? "UNKNOWN"} ${error?.message ?? ""}`
                );
            }
            return;
        }

        if (typeof msg.event === "string") {
            for (const handler of this.eventHandlers) {
                try {
                    handler(msg);
                } catch (err) {
                    console.error(`${this.logTag} event handler threw: ${(err as Error).message}`);
                }
            }
            return;
        }

        const id = typeof msg.id === "string" ? msg.id : String(msg.id);
        const entry = this.pending.get(id);
        if (!entry) return;
        this.pending.delete(id);
        clearTimeout(entry.timeoutHandle);

        if (msg.ok) {
            entry.resolve(msg.result);
        } else {
            const error = msg.error as { code?: string; message?: string } | undefined;
            entry.reject(new PluginError(error?.code ?? "INTERNAL", error?.message ?? "unknown plugin error"));
        }
    }

    /** Sends the `subscribe` RPC after a successful auth, if `subscribeEvents` was configured. Never tears the connection down on failure. */
    private sendSubscribe(): void {
        if (this.subscribeEvents.length === 0) return;
        const id = `sub-${this.nextSubId++}`;
        this.sendRaw({ id, method: "subscribe", params: { events: this.subscribeEvents } });
        const timeoutHandle = setTimeout(() => {
            this.pending.delete(id);
            console.error(`${this.logTag} subscribe timed out`);
        }, this.defaultTimeoutMs);
        this.pending.set(id, {
            resolve: result => {
                console.error(`${this.logTag} subscribe ok: ${JSON.stringify(result)}`);
            },
            reject: err => {
                console.error(`${this.logTag} subscribe failed: ${err.message}`);
            },
            timeoutHandle
        });
    }

    private handleProgress(msg: Record<string, unknown>): void {
        const id = String(msg.id);
        const now = Date.now();
        const last = this.lastProgressLogAt.get(id) ?? 0;
        if (now - last >= 2000) {
            this.lastProgressLogAt.set(id, now);
            console.error(`${this.logTag} progress ${id}: ${msg.done}/${msg.total}`);
        }
    }
}
