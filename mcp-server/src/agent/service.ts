// SPDX-License-Identifier: AGPL-3.0-or-later

import type { PluginClient } from "../plugin-client.js";
import type { AgentConfig } from "./config.js";
import { createHistory } from "./history.js";
import { runRequest, type PlayerInfo } from "./runner.js";
import { createToolBridge } from "./tools.js";

const PROGRESS_THROTTLE_MS = 1500;
const CHUNK_MAX_LENGTH = 1000;

interface QueueEntry {
    requestId: string;
    player: PlayerInfo;
    text: string;
    controller: AbortController;
}

interface PlayerState {
    queue: QueueEntry[];
    running: QueueEntry | null;
}

/** Splits `text` into chunks of at most `max` characters, preferring to cut on a line break, then a space. */
function chunkText(text: string, max: number): string[] {
    if (text.length <= max) return [text];
    const chunks: string[] = [];
    let rest = text;
    while (rest.length > max) {
        let cut = rest.lastIndexOf("\n", max);
        if (cut <= 0) cut = rest.lastIndexOf(" ", max);
        if (cut <= 0) cut = max;
        chunks.push(rest.slice(0, cut));
        rest = rest.slice(cut).replace(/^[\n ]+/, "");
    }
    if (rest.length > 0) chunks.push(rest);
    return chunks;
}

export interface AgentService {
    close(): void;
}

/**
 * Wires the plugin's `chat`/`chat_cancel` events (docs/prompts/
 * step6b-prompt.md) to {@link runRequest}: enforces the per-player daily
 * request count, a per-player serial queue, and a global concurrency cap;
 * sends "Working on it...", throttled progress lines, and the final reply
 * (chunked to <= 1000 characters) back via the plugin's `send_message` RPC.
 */
export async function startAgentService(
    pluginClient: PluginClient,
    cfg: AgentConfig
): Promise<AgentService & { toolNames: string[] }> {
    const bridge = await createToolBridge(pluginClient, { allowCommand: cfg.allowCommand });
    const history = createHistory({ turns: cfg.historyTurns, ttlMinutes: cfg.historyTtlMinutes });

    const playerStates = new Map<string, PlayerState>();
    const dailyCounts = new Map<string, { day: string; count: number }>();
    let activeCount = 0;
    const waitQueue: Array<() => void> = [];
    let closed = false;

    function today(): string {
        return new Date().toISOString().slice(0, 10);
    }

    function checkAndIncrementDaily(uuid: string): boolean {
        if (cfg.maxRequestsPerPlayerPerDay === 0) return true;
        const day = today();
        let entry = dailyCounts.get(uuid);
        if (!entry || entry.day !== day) {
            entry = { day, count: 0 };
            dailyCounts.set(uuid, entry);
        }
        if (entry.count >= cfg.maxRequestsPerPlayerPerDay) return false;
        entry.count++;
        return true;
    }

    async function send(uuid: string, text: string): Promise<void> {
        try {
            await pluginClient.request("send_message", { player: uuid, text });
        } catch (err) {
            console.error(`[agent-service] send_message to ${uuid} failed: ${(err as Error).message}`);
        }
    }

    async function acquireSlot(): Promise<void> {
        if (activeCount < cfg.maxConcurrent) {
            activeCount++;
            return;
        }
        await new Promise<void>(resolve => waitQueue.push(resolve));
        activeCount++;
    }

    function releaseSlot(): void {
        activeCount--;
        const next = waitQueue.shift();
        if (next) next();
    }

    async function processEntry(entry: QueueEntry): Promise<void> {
        await acquireSlot();
        let progressTimer: ReturnType<typeof setTimeout> | null = null;
        try {
            await send(entry.player.uuid, "Working on it...");

            let lastProgressAt = 0;
            let pendingLine: string | null = null;

            const flush = () => {
                progressTimer = null;
                if (pendingLine !== null) {
                    const line = pendingLine;
                    pendingLine = null;
                    lastProgressAt = Date.now();
                    void send(entry.player.uuid, line);
                }
            };

            const onProgress = (line: string) => {
                const now = Date.now();
                if (now - lastProgressAt >= PROGRESS_THROTTLE_MS) {
                    lastProgressAt = now;
                    void send(entry.player.uuid, line);
                } else {
                    pendingLine = line;
                    if (!progressTimer) {
                        progressTimer = setTimeout(flush, PROGRESS_THROTTLE_MS - (now - lastProgressAt));
                    }
                }
            };

            let finalText: string;
            try {
                finalText = await runRequest({
                    cfg,
                    bridge,
                    history,
                    player: entry.player,
                    text: entry.text,
                    signal: entry.controller.signal,
                    onProgress
                });
            } catch (err) {
                console.error(
                    `[agent-service] request ${entry.requestId} for ${entry.player.name} failed: ${(err as Error).stack ?? err}`
                );
                const message = err instanceof Error ? err.message : String(err);
                finalText = `Something went wrong: ${message.slice(0, 200)}`;
            }

            if (progressTimer) {
                clearTimeout(progressTimer);
                progressTimer = null;
            }

            for (const chunk of chunkText(finalText, CHUNK_MAX_LENGTH)) {
                await send(entry.player.uuid, chunk);
            }
        } finally {
            if (progressTimer) clearTimeout(progressTimer);
            releaseSlot();
        }
    }

    function startEntry(state: PlayerState, entry: QueueEntry): void {
        state.running = entry;
        void processEntry(entry).finally(() => {
            state.running = null;
            const next = state.queue.shift();
            if (next) startEntry(state, next);
        });
    }

    function handleChat(event: Record<string, unknown>): void {
        if (closed) return;
        const player = event.player as PlayerInfo | undefined;
        const text = event.text as string | undefined;
        const requestId = typeof event.requestId === "string" ? event.requestId : `chat-${Date.now()}`;
        if (!player || typeof player.uuid !== "string" || typeof text !== "string") return;

        if (!checkAndIncrementDaily(player.uuid)) {
            void send(player.uuid, `Daily request limit reached (${cfg.maxRequestsPerPlayerPerDay}/day). Try again tomorrow.`);
            return;
        }

        let state = playerStates.get(player.uuid);
        if (!state) {
            state = { queue: [], running: null };
            playerStates.set(player.uuid, state);
        }

        const entry: QueueEntry = { requestId, player, text, controller: new AbortController() };

        if (state.running) {
            state.queue.push(entry);
            void send(player.uuid, "Queued behind your previous request.");
            return;
        }

        startEntry(state, entry);
    }

    function handleChatCancel(event: Record<string, unknown>): void {
        const playerRef = event.player as { uuid?: string } | undefined;
        const uuid = playerRef?.uuid;
        if (typeof uuid !== "string") return;

        const state = playerStates.get(uuid);
        if (state?.running) {
            state.running.controller.abort();
            void send(uuid, "Cancelled.");
            return;
        }
        if (state && state.queue.length > 0) {
            state.queue = [];
            void send(uuid, "Cancelled.");
            return;
        }
        void send(uuid, "Nothing to cancel.");
    }

    pluginClient.onEvent(event => {
        if (event.event === "chat") {
            handleChat(event);
        } else if (event.event === "chat_cancel") {
            handleChatCancel(event);
        }
    });

    return {
        close: () => {
            closed = true;
            bridge.close();
        },
        toolNames: bridge.tools.map(t => t.function.name)
    };
}
