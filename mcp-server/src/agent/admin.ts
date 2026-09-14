// SPDX-License-Identifier: AGPL-3.0-or-later

import { fmtCost, fmtTokens, type LimitKind, type LimitValue, type UsageStore, type UsageSummary } from "./usage.js";

/** The plugin's `admin` event target: `uuid` is null when the named player is offline. */
export interface AdminTarget {
    name: string;
    uuid: string | null;
}

export interface AdminEvent {
    event: "admin";
    action: "usage" | "limit" | "cancel" | "pause" | "resume";
    by: { name: string; uuid: string };
    target: AdminTarget | null;
    args: string[];
}

/** Outcome of trying to cancel a player's request, from the caller's playerStates map. */
export type CancelOutcome = "running" | "queued" | "none";

export interface AdminHandlerOptions {
    store: UsageStore;
    cancel: (uuid: string) => CancelOutcome;
    send: (uuid: string, text: string, kind?: "progress" | "final") => Promise<void>;
    currency: string;
}

const MAX_USAGE_ALL_LINES = 20;
const LIMIT_KINDS = ["cost", "tokens", "requests"] as const;

function fmtLimitValue(kind: LimitKind, value: LimitValue, currency: string): string {
    if (value === "off") return `unlimited ${kind}`;
    if (kind === "cost") return `${fmtCost(value, currency)}/day`;
    if (kind === "tokens") return `${fmtTokens(value)} tokens/day`;
    return `${value} requests/day`;
}

function fmtLimitsLine(
    limits: Record<LimitKind, LimitValue>,
    currency: string,
    overrides?: Record<LimitKind, boolean>
): string {
    return LIMIT_KINDS.map(kind => {
        const text = fmtLimitValue(kind, limits[kind], currency);
        return overrides?.[kind] ? `${text} (override)` : text;
    }).join(", ");
}

function fmtUsageBlock(s: UsageSummary, currency: string): string {
    const todayTokens = fmtTokens(s.today.inputTokens + s.today.cachedInputTokens + s.today.outputTokens);
    const totalTokens = fmtTokens(s.total.inputTokens + s.total.cachedInputTokens + s.total.outputTokens);
    return [
        `Usage for ${s.name}:`,
        `today: ${s.today.requests} requests, ${todayTokens} tokens, ${fmtCost(s.today.cost, currency)}`,
        `total: ${s.total.requests} requests, ${totalTokens} tokens, ${fmtCost(s.total.cost, currency)}`,
        `limits: ${fmtLimitsLine(s.limits, currency, s.overrides)}`
    ].join("\n");
}

/**
 * Resolves an `admin` event's non-null target to a `{uuid, name}` pair:
 * uses `target.uuid` directly when the plugin supplied one (the player is
 * online), otherwise falls back to the usage store's last-seen name lookup.
 */
function resolveTarget(store: UsageStore, target: AdminTarget): { uuid: string; name: string } | null {
    if (target.uuid) return { uuid: target.uuid, name: target.name };
    const found = store.findByName(target.name);
    return found ?? null;
}

/**
 * Builds the handler for the plugin's `admin` chat-subscription event
 * (docs/private/prompts/step6f-prompt.md): `usage`, `limit`, `cancel`,
 * `pause`, `resume`. Structured as a plain function of `{store, cancel,
 * send}` so it can be unit tested without a `PluginClient` or a live queue.
 */
export function createAdminHandler(opts: AdminHandlerOptions): (event: Record<string, unknown>) => Promise<void> {
    const { store, cancel, send, currency } = opts;

    async function handleUsage(by: { name: string; uuid: string }, target: AdminTarget | null): Promise<void> {
        if (target === null) {
            await send(by.uuid, fmtUsageBlock(store.summary(by.uuid, by.name), currency), "final");
            return;
        }

        if (target.name.toLowerCase() === "all") {
            const all = store.summaryAll();
            if (all.length === 0) {
                await send(by.uuid, "No usage recorded yet.", "final");
                return;
            }
            const shown = all.slice(0, MAX_USAGE_ALL_LINES);
            const lines = shown.map(s => {
                const tokens = fmtTokens(s.today.inputTokens + s.today.cachedInputTokens + s.today.outputTokens);
                return `${s.name}: ${s.today.requests} requests, ${tokens} tokens, ${fmtCost(s.today.cost, currency)}`;
            });
            const header = "Usage today, by player (sorted by cost):";
            const truncated =
                all.length > MAX_USAGE_ALL_LINES ? [`... and ${all.length - MAX_USAGE_ALL_LINES} more (showing top ${MAX_USAGE_ALL_LINES})`] : [];
            await send(by.uuid, [header, ...lines, ...truncated].join("\n"), "final");
            return;
        }

        const resolved = resolveTarget(store, target);
        if (!resolved) {
            await send(by.uuid, `Unknown player "${target.name}".`, "final");
            return;
        }
        await send(by.uuid, fmtUsageBlock(store.summary(resolved.uuid, resolved.name), currency), "final");
    }

    async function handleLimit(by: { name: string; uuid: string }, target: AdminTarget | null, args: string[]): Promise<void> {
        let uuidOrNull: string | null;
        let who: string;
        let nameForCreate: string | undefined;
        if (target === null) {
            uuidOrNull = null;
            who = "the server default";
        } else {
            const resolved = resolveTarget(store, target);
            if (!resolved) {
                await send(by.uuid, `Unknown player "${target.name}".`, "final");
                return;
            }
            uuidOrNull = resolved.uuid;
            who = resolved.name;
            nameForCreate = resolved.name;
        }

        if (args[0] === "reset") {
            store.resetLimits(uuidOrNull);
            await send(by.uuid, `Reset limits for ${who}: ${fmtLimitsLine(store.effectiveLimits(uuidOrNull), currency)}`, "final");
            return;
        }

        const kind = args[0];
        if (kind !== "cost" && kind !== "tokens" && kind !== "requests") {
            await send(by.uuid, `Unknown limit kind "${kind ?? ""}". Use cost, tokens, requests, or reset.`, "final");
            return;
        }

        const rawValue = args[1];
        let value: LimitValue;
        if (rawValue === "off") {
            value = "off";
        } else {
            const n = Number(rawValue);
            if (!Number.isFinite(n) || n <= 0) {
                await send(by.uuid, `Limit value must be a positive number or "off" (got "${rawValue ?? ""}").`, "final");
                return;
            }
            value = n;
        }

        store.setLimit(uuidOrNull, kind, value, nameForCreate);
        await send(by.uuid, `Set limits for ${who}: ${fmtLimitsLine(store.effectiveLimits(uuidOrNull), currency)}`, "final");
    }

    async function handleCancel(by: { name: string; uuid: string }, target: AdminTarget | null): Promise<void> {
        if (target === null) {
            await send(by.uuid, "cancel needs a target player.", "final");
            return;
        }
        const uuid = target.uuid ?? store.findByName(target.name)?.uuid;
        const outcome = uuid ? cancel(uuid) : "none";
        if (outcome === "none") {
            await send(by.uuid, `${target.name} has no request running.`, "final");
            return;
        }
        await send(by.uuid, `Cancelled ${target.name}'s request.`, "final");
        if (uuid) {
            await send(uuid, `Your request was cancelled by ${by.name}.`, "final");
        }
    }

    async function handlePauseResume(by: { name: string; uuid: string }, resume: boolean): Promise<void> {
        store.setPaused(!resume);
        const message = resume ? "Assistant resumed." : "Assistant paused. New requests are rejected until /ashlar resume.";
        await send(by.uuid, message, "final");
    }

    return async function handleAdminEvent(event: Record<string, unknown>): Promise<void> {
        const by = event.by as { name: string; uuid: string } | undefined;
        if (!by || typeof by.uuid !== "string" || typeof by.name !== "string") return;
        const target = (event.target ?? null) as AdminTarget | null;
        const args = Array.isArray(event.args) ? (event.args as string[]) : [];

        switch (event.action) {
            case "usage":
                await handleUsage(by, target);
                return;
            case "limit":
                await handleLimit(by, target, args);
                return;
            case "cancel":
                await handleCancel(by, target);
                return;
            case "pause":
                await handlePauseResume(by, false);
                return;
            case "resume":
                await handlePauseResume(by, true);
                return;
            default:
                console.error(`[agent-admin] unknown action "${String(event.action)}"`);
        }
    };
}
