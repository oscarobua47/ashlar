// SPDX-License-Identifier: AGPL-3.0-or-later

import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

import { priceMultiplier, type PeakSchedule } from "./pricing.js";

export type LimitKind = "cost" | "tokens" | "requests";
/** A concrete per-day cap, or "off" for unlimited. */
export type LimitValue = number | "off";

export interface DayCounters {
    /** UTC `YYYY-MM-DD`. */
    date: string;
    requests: number;
    inputTokens: number;
    cachedInputTokens: number;
    outputTokens: number;
    cost: number;
}

export interface TotalCounters {
    requests: number;
    inputTokens: number;
    cachedInputTokens: number;
    outputTokens: number;
    cost: number;
}

export interface UsageSummary {
    uuid: string;
    name: string;
    today: DayCounters;
    total: TotalCounters;
    limits: Record<LimitKind, LimitValue>;
    /** Whether each kind is set on this player specifically (vs. inherited from the store default or the env default). */
    overrides: Record<LimitKind, boolean>;
}

export interface RecordResult {
    /** Cost of just this call, in the store's configured currency. */
    cost: number;
    today: DayCounters;
    total: TotalCounters;
}

interface PlayerRecord {
    name: string;
    limits: Partial<Record<LimitKind, LimitValue>>;
    today: DayCounters;
    total: TotalCounters;
    /** Epoch ms of the last time this record was touched; used by findByName to break name collisions. Not part of the documented v1 shape, but harmless to persist. */
    updatedAt: number;
}

interface StoreFile {
    version: 1;
    paused: boolean;
    defaults: Partial<Record<LimitKind, LimitValue>>;
    players: Record<string, PlayerRecord>;
}

export interface UsageStoreOptions {
    filePath: string;
    priceInput: number;
    priceCachedInput: number;
    priceOutput: number;
    currency: string;
    /** Env-level defaults, 0 = unlimited (matches AgentConfig's existing convention). */
    envLimits: { cost: number; tokens: number; requests: number };
    peakSchedule: PeakSchedule;
    offPeakMultiplier: number;
    /** Test seam: defaults to Date.now-based real time. */
    now?: () => Date;
    /** Test seam: defaults to 500ms. */
    saveDebounceMs?: number;
}

function freshDay(date: string): DayCounters {
    return { date, requests: 0, inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: 0 };
}

function freshTotal(): TotalCounters {
    return { requests: 0, inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: 0 };
}

function isLimitValue(v: unknown): v is LimitValue {
    return v === "off" || (typeof v === "number" && Number.isFinite(v) && v > 0);
}

/** Best-effort validation of a loaded file; on any doubt the caller treats the file as corrupt. */
function isValidStoreFile(obj: unknown): obj is StoreFile {
    if (!obj || typeof obj !== "object") return false;
    const f = obj as Record<string, unknown>;
    if (f.version !== 1) return false;
    if (typeof f.paused !== "boolean") return false;
    if (typeof f.defaults !== "object" || f.defaults === null) return false;
    if (typeof f.players !== "object" || f.players === null) return false;
    return true;
}

/**
 * Persists per-player token/cost usage, per-day limit overrides and the
 * global pause flag (docs/private/prompts/step6f-prompt.md). Loaded
 * synchronously at construction; saved atomically (`<file>.tmp` then
 * rename), debounced, and on {@link close}.
 */
export class UsageStore {
    private readonly filePath: string;
    private readonly priceInput: number;
    private readonly priceCachedInput: number;
    private readonly priceOutput: number;
    private readonly currency: string;
    private readonly envLimits: { cost: number; tokens: number; requests: number };
    private readonly peakSchedule: PeakSchedule;
    private readonly offPeakMultiplier: number;
    private readonly now: () => Date;
    private readonly saveDebounceMs: number;

    private paused = false;
    private defaults: Partial<Record<LimitKind, LimitValue>> = {};
    private players = new Map<string, PlayerRecord>();
    private saveTimer: ReturnType<typeof setTimeout> | null = null;
    private dirty = false;

    constructor(opts: UsageStoreOptions) {
        this.filePath = opts.filePath;
        this.priceInput = opts.priceInput;
        this.priceCachedInput = opts.priceCachedInput;
        this.priceOutput = opts.priceOutput;
        this.currency = opts.currency;
        this.envLimits = opts.envLimits;
        this.peakSchedule = opts.peakSchedule;
        this.offPeakMultiplier = opts.offPeakMultiplier;
        this.now = opts.now ?? (() => new Date());
        this.saveDebounceMs = opts.saveDebounceMs ?? 500;
        this.load();
    }

    private load(): void {
        let raw: string;
        try {
            raw = readFileSync(this.filePath, "utf8");
        } catch (err) {
            if ((err as NodeJS.ErrnoException).code === "ENOENT") {
                return; // Missing file: empty store.
            }
            console.error(`[agent-usage] could not read ${this.filePath}: ${(err as Error).message}; starting empty`);
            return;
        }

        let parsed: unknown;
        try {
            parsed = JSON.parse(raw);
        } catch (err) {
            this.backupCorruptFile(`invalid JSON: ${(err as Error).message}`);
            return;
        }

        if (!isValidStoreFile(parsed)) {
            this.backupCorruptFile("unexpected shape");
            return;
        }

        this.paused = parsed.paused;
        this.defaults = {};
        for (const kind of ["cost", "tokens", "requests"] as const) {
            const v = parsed.defaults[kind];
            if (isLimitValue(v)) this.defaults[kind] = v;
        }
        this.players = new Map();
        for (const [uuid, rec] of Object.entries(parsed.players)) {
            if (!rec || typeof rec !== "object") continue;
            const r = rec as Partial<PlayerRecord>;
            if (typeof r.name !== "string" || !r.today || !r.total) continue;
            const limits: Partial<Record<LimitKind, LimitValue>> = {};
            for (const kind of ["cost", "tokens", "requests"] as const) {
                const v = r.limits?.[kind];
                if (isLimitValue(v)) limits[kind] = v;
            }
            this.players.set(uuid, {
                name: r.name,
                limits,
                today: { ...freshDay(this.todayStr()), ...r.today },
                total: { ...freshTotal(), ...r.total },
                updatedAt: typeof r.updatedAt === "number" ? r.updatedAt : 0
            });
        }
    }

    private backupCorruptFile(reason: string): void {
        const backupPath = `${this.filePath}.corrupt-${Date.now()}`;
        console.error(`[agent-usage] ${this.filePath} is corrupt (${reason}); backing up to ${backupPath} and starting empty`);
        try {
            renameSync(this.filePath, backupPath);
        } catch (err) {
            console.error(`[agent-usage] could not back up corrupt file: ${(err as Error).message}`);
        }
        this.paused = false;
        this.defaults = {};
        this.players = new Map();
    }

    private scheduleSave(): void {
        this.dirty = true;
        if (this.saveTimer) return;
        this.saveTimer = setTimeout(() => {
            this.saveTimer = null;
            this.saveNow();
        }, this.saveDebounceMs);
        // Node keeps the process alive for a pending timer; do not block shutdown on it.
        this.saveTimer.unref?.();
    }

    private saveNow(): void {
        if (!this.dirty) return;
        this.dirty = false;
        const file: StoreFile = {
            version: 1,
            paused: this.paused,
            defaults: this.defaults,
            players: Object.fromEntries(this.players)
        };
        const json = JSON.stringify(file, null, 2);
        const tmpPath = `${this.filePath}.tmp`;
        try {
            mkdirSync(dirname(this.filePath), { recursive: true });
            writeFileSync(tmpPath, json, "utf8");
            renameSync(tmpPath, this.filePath);
        } catch (err) {
            console.error(`[agent-usage] could not save ${this.filePath}: ${(err as Error).message}`);
        }
    }

    /** Flushes any pending save synchronously. Call once at shutdown. */
    close(): void {
        if (this.saveTimer) {
            clearTimeout(this.saveTimer);
            this.saveTimer = null;
        }
        this.saveNow();
    }

    private todayStr(): string {
        return this.now().toISOString().slice(0, 10);
    }

    private rollover(rec: PlayerRecord): void {
        const day = this.todayStr();
        if (rec.today.date !== day) {
            rec.today = freshDay(day);
        }
    }

    private getOrCreatePlayer(uuid: string, name: string): PlayerRecord {
        let rec = this.players.get(uuid);
        if (!rec) {
            rec = { name, limits: {}, today: freshDay(this.todayStr()), total: freshTotal(), updatedAt: this.now().getTime() };
            this.players.set(uuid, rec);
        } else {
            rec.name = name;
            this.rollover(rec);
        }
        return rec;
    }

    private resolveLimit(playerValue: LimitValue | undefined, defaultValue: LimitValue | undefined, envValue: number): LimitValue {
        if (playerValue !== undefined) return playerValue;
        if (defaultValue !== undefined) return defaultValue;
        return envValue === 0 ? "off" : envValue;
    }

    /** The effective per-day cap for `uuid` and `kind`: player override > store default > env default. */
    effectiveLimit(uuid: string, kind: LimitKind): LimitValue {
        const rec = this.players.get(uuid);
        return this.resolveLimit(rec?.limits[kind], this.defaults[kind], this.envLimits[kind]);
    }

    /** All three effective limits at once, for `uuid`, or for the server default when `uuidOrNull` is null. */
    effectiveLimits(uuidOrNull: string | null): Record<LimitKind, LimitValue> {
        if (uuidOrNull === null) {
            return {
                cost: this.resolveLimit(undefined, this.defaults.cost, this.envLimits.cost),
                tokens: this.resolveLimit(undefined, this.defaults.tokens, this.envLimits.tokens),
                requests: this.resolveLimit(undefined, this.defaults.requests, this.envLimits.requests)
            };
        }
        return {
            cost: this.effectiveLimit(uuidOrNull, "cost"),
            tokens: this.effectiveLimit(uuidOrNull, "tokens"),
            requests: this.effectiveLimit(uuidOrNull, "requests")
        };
    }

    /**
     * Whether `uuid` may start a new request right now, per today's counters
     * against the effective limits. Never called mid-request: a running
     * request is never interrupted by a limit newly being reached.
     */
    checkAllowed(uuid: string): { ok: true } | { ok: false; reason: string } {
        const rec = this.players.get(uuid);
        if (rec) this.rollover(rec);
        const today = rec ? rec.today : freshDay(this.todayStr());

        const requestsLimit = this.effectiveLimit(uuid, "requests");
        if (requestsLimit !== "off" && today.requests >= requestsLimit) {
            return { ok: false, reason: `daily request limit (${requestsLimit}) reached` };
        }

        const tokensLimit = this.effectiveLimit(uuid, "tokens");
        if (tokensLimit !== "off") {
            const usedTokens = today.inputTokens + today.cachedInputTokens + today.outputTokens;
            if (usedTokens >= tokensLimit) {
                return { ok: false, reason: `daily token limit (${fmtTokens(tokensLimit)}) reached` };
            }
        }

        const costLimit = this.effectiveLimit(uuid, "cost");
        if (costLimit !== "off" && today.cost >= costLimit) {
            return { ok: false, reason: `daily cost limit (${fmtCost(costLimit, this.currency)}) reached` };
        }

        return { ok: true };
    }

    /** Adds one request's usage to `uuid`'s today/total counters and computes its cost at the current price multiplier. */
    record(uuid: string, name: string, usage: { inputTokens: number; cachedInputTokens: number; outputTokens: number }): RecordResult {
        const rec = this.getOrCreatePlayer(uuid, name);
        const multiplier = priceMultiplier(this.peakSchedule, this.offPeakMultiplier, this.now());
        const cost =
            (usage.inputTokens / 1_000_000) * this.priceInput * multiplier +
            (usage.cachedInputTokens / 1_000_000) * this.priceCachedInput * multiplier +
            (usage.outputTokens / 1_000_000) * this.priceOutput * multiplier;

        for (const bucket of [rec.today, rec.total]) {
            bucket.requests += 1;
            bucket.inputTokens += usage.inputTokens;
            bucket.cachedInputTokens += usage.cachedInputTokens;
            bucket.outputTokens += usage.outputTokens;
            bucket.cost += cost;
        }
        rec.updatedAt = this.now().getTime();
        this.scheduleSave();

        return { cost, today: { ...rec.today }, total: { ...rec.total } };
    }

    /** Sets a per-day cap for `uuid`, or the store default when `uuidOrNull` is null. `name` is used only when creating a not-yet-seen player. */
    setLimit(uuidOrNull: string | null, kind: LimitKind, value: LimitValue, name?: string): void {
        if (uuidOrNull === null) {
            this.defaults[kind] = value;
        } else {
            const rec = this.getOrCreatePlayer(uuidOrNull, name ?? this.players.get(uuidOrNull)?.name ?? uuidOrNull);
            rec.limits[kind] = value;
        }
        this.scheduleSave();
    }

    /** Removes all limit overrides for `uuid`, or all store defaults when `uuidOrNull` is null. */
    resetLimits(uuidOrNull: string | null): void {
        if (uuidOrNull === null) {
            this.defaults = {};
        } else {
            const rec = this.players.get(uuidOrNull);
            if (rec) rec.limits = {};
        }
        this.scheduleSave();
    }

    setPaused(paused: boolean): void {
        this.paused = paused;
        this.scheduleSave();
    }

    isPaused(): boolean {
        return this.paused;
    }

    /** Case-insensitive lookup by the last-seen name for that uuid (ties broken by most recently updated). */
    findByName(name: string): { uuid: string; name: string } | undefined {
        const lower = name.toLowerCase();
        let best: { uuid: string; name: string; updatedAt: number } | undefined;
        for (const [uuid, rec] of this.players) {
            if (rec.name.toLowerCase() !== lower) continue;
            if (!best || rec.updatedAt > best.updatedAt) {
                best = { uuid, name: rec.name, updatedAt: rec.updatedAt };
            }
        }
        return best ? { uuid: best.uuid, name: best.name } : undefined;
    }

    /** A usage summary for `uuid`, synthesising zero counters if no request has been recorded yet. */
    summary(uuid: string, nameFallback?: string): UsageSummary {
        const rec = this.players.get(uuid);
        if (rec) this.rollover(rec);
        return {
            uuid,
            name: rec?.name ?? nameFallback ?? uuid,
            today: rec ? { ...rec.today } : freshDay(this.todayStr()),
            total: rec ? { ...rec.total } : freshTotal(),
            limits: this.effectiveLimits(uuid),
            overrides: {
                cost: rec?.limits.cost !== undefined,
                tokens: rec?.limits.tokens !== undefined,
                requests: rec?.limits.requests !== undefined
            }
        };
    }

    /** Every known player's summary, sorted by today's cost descending. */
    summaryAll(): UsageSummary[] {
        return [...this.players.keys()].map(uuid => this.summary(uuid)).sort((a, b) => b.today.cost - a.today.cost);
    }
}

/** `21.9k`, `1.2M`, plain integer below 1000; trailing `.0` is dropped (`500000` -> `500k`). */
export function fmtTokens(n: number): string {
    if (n < 1000) return String(Math.round(n));
    const useMillions = n >= 1_000_000;
    const scaled = (n / (useMillions ? 1_000_000 : 1000)).toFixed(1);
    const trimmed = scaled.endsWith(".0") ? scaled.slice(0, -2) : scaled;
    return `${trimmed}${useMillions ? "M" : "k"}`;
}

/** 2 decimals normally, 4 when the amount is a nonzero value below 0.01; `currency` is `$`-prefixed for USD, `<code> `-prefixed otherwise. */
export function fmtCost(amount: number, currency: string): string {
    const decimals = amount > 0 && amount < 0.01 ? 4 : 2;
    const formatted = amount.toFixed(decimals);
    return currency === "USD" ? `$${formatted}` : `${currency} ${formatted}`;
}
