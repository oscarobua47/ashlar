// SPDX-License-Identifier: AGPL-3.0-or-later

import type { PluginClient } from "../plugin-client.js";
import type { Catalog } from "../server.js";
import { createAdminHandler, type CancelOutcome } from "./admin.js";
import type { AgentConfig } from "./config.js";
import { createHistory } from "./history.js";
import type { CallUsage } from "./provider.js";
import { runRequest, type PlayerInfo } from "./runner.js";
import { createToolBridge } from "./tools.js";
import { fmtCost, fmtTokens, UsageStore, type DayCounters, type LimitValue } from "./usage.js";

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

/**
 * The `(this request: ... | today: ...)` footer appended to every final
 * reply (docs/private/prompts/step6f-prompt.md): `of <limit>` is omitted
 * when there is no cost limit, and cost is replaced by tokens when every
 * AI_PRICE_* is 0 (e.g. a free local model).
 */
function formatUsageFooter(requestCost: number, requestUsage: CallUsage, today: DayCounters, cfg: AgentConfig, costLimit: LimitValue): string {
    const requestTokens = requestUsage.inputTokens + requestUsage.cachedInputTokens + requestUsage.outputTokens;
    const todayTokens = today.inputTokens + today.cachedInputTokens + today.outputTokens;
    const pricesAreZero = cfg.priceInput === 0 && cfg.priceCachedInput === 0 && cfg.priceOutput === 0;

    if (pricesAreZero) {
        return `(this request: ${fmtTokens(requestTokens)} tokens | today: ${fmtTokens(todayTokens)} tokens)`;
    }

    const ofPart = costLimit === "off" ? "" : ` of ${fmtCost(costLimit, cfg.currency)}`;
    return `(this request: ${fmtTokens(requestTokens)} tokens, ${fmtCost(requestCost, cfg.currency)} | today: ${fmtCost(today.cost, cfg.currency)}${ofPart})`;
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
 * Wires the plugin's `chat`/`chat_cancel`/`admin` events (docs/private/
 * prompts/step6b-prompt.md, step6f-prompt.md) to {@link runRequest}:
 * enforces per-player daily request/token/cost limits (with per-player
 * overrides and a global pause switch, all persisted via {@link UsageStore}),
 * a per-player serial queue, and a global concurrency cap; sends "Working on
 * it...", throttled progress lines, and the final reply (chunked to <= 1000
 * characters, with a usage footer) back via the plugin's `send_message` RPC.
 * Every call passes a `kind`: `"final"` only for the finished reply's
 * chunks, `"progress"` for everything else (step6d-prompt.md) - the plugin
 * uses this to decide what `ashlar.monitor` players get to see.
 */
export async function startAgentService(
    pluginClient: PluginClient,
    cfg: AgentConfig,
    catalog: Catalog
): Promise<AgentService & { toolNames: string[] }> {
    const bridge = await createToolBridge(pluginClient, { allowCommand: cfg.allowCommand }, catalog);
    const history = createHistory({ turns: cfg.historyTurns, ttlMinutes: cfg.historyTtlMinutes });
    const usageStore = new UsageStore({
        filePath: cfg.usageFile,
        priceInput: cfg.priceInput,
        priceCachedInput: cfg.priceCachedInput,
        priceOutput: cfg.priceOutput,
        currency: cfg.currency,
        envLimits: { cost: cfg.maxCostPerPlayerPerDay, tokens: cfg.maxTokensPerPlayerPerDay, requests: cfg.maxRequestsPerPlayerPerDay },
        peakSchedule: cfg.peakHours,
        offPeakMultiplier: cfg.offPeakMultiplier
    });

    const playerStates = new Map<string, PlayerState>();
    let activeCount = 0;
    const waitQueue: Array<() => void> = [];
    let closed = false;

    async function send(uuid: string, text: string, kind: "progress" | "final" = "progress"): Promise<void> {
        try {
            await pluginClient.request("send_message", { player: uuid, text, kind });
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
            if (usageStore.isPaused()) {
                await send(entry.player.uuid, "The assistant is paused by an operator.", "final");
                return;
            }
            const allowed = usageStore.checkAllowed(entry.player.uuid);
            if (!allowed.ok) {
                await send(entry.player.uuid, allowed.reason, "final");
                return;
            }

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
                const result = await runRequest({
                    cfg,
                    bridge,
                    history,
                    player: entry.player,
                    text: entry.text,
                    signal: entry.controller.signal,
                    onProgress
                });
                const recorded = usageStore.record(entry.player.uuid, entry.player.name, result.usage);
                const costLimit = usageStore.effectiveLimits(entry.player.uuid).cost;
                const footer = formatUsageFooter(recorded.cost, result.usage, recorded.today, cfg, costLimit);
                finalText = `${result.text}\n${footer}`;
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
                await send(entry.player.uuid, chunk, "final");
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

        // Pause/limit checks happen once the request actually starts (in
        // processEntry), not here: a player's own queue is serial, so an
        // earlier queued request can change today's counters first.
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

    /** Cancels `uuid`'s running or queued request, if any. Shared by `/ashlar cancel` (own request) and the `admin cancel` action (another player's). */
    function cancelPlayerRequest(uuid: string): CancelOutcome {
        const state = playerStates.get(uuid);
        if (state?.running) {
            state.running.controller.abort();
            return "running";
        }
        if (state && state.queue.length > 0) {
            state.queue = [];
            return "queued";
        }
        return "none";
    }

    function handleChatCancel(event: Record<string, unknown>): void {
        const playerRef = event.player as { uuid?: string } | undefined;
        const uuid = playerRef?.uuid;
        if (typeof uuid !== "string") return;

        const outcome = cancelPlayerRequest(uuid);
        void send(uuid, outcome === "none" ? "Nothing to cancel." : "Cancelled.");
    }

    const handleAdmin = createAdminHandler({
        store: usageStore,
        cancel: cancelPlayerRequest,
        send,
        currency: cfg.currency
    });

    pluginClient.onEvent(event => {
        if (event.event === "chat") {
            handleChat(event);
        } else if (event.event === "chat_cancel") {
            handleChatCancel(event);
        } else if (event.event === "admin") {
            void handleAdmin(event);
        }
    });

    return {
        close: () => {
            closed = true;
            usageStore.close();
            bridge.close();
        },
        toolNames: bridge.tools.map(t => t.function.name)
    };
}
