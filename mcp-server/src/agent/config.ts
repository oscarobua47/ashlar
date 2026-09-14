// SPDX-License-Identifier: AGPL-3.0-or-later

import { readFileSync } from "node:fs";

import { ConfigError } from "../config.js";
import { parsePeakHours, type PeakSchedule } from "./pricing.js";

/** Environment configuration for `ashlar-mcp --agent`. Read once at startup; see docs/prompts/step6b-prompt.md. */
export interface AgentConfig {
    /** OpenAI-compatible base URL, no trailing slash; "/chat/completions" is appended by the caller. */
    baseUrl: string;
    apiKey: string;
    model: string;
    /** Per-request tool-call budget; when reached the model gets one last turn with tool_choice:"none". */
    maxToolCalls: number;
    /** Per-player daily request cap, counted by UUID and reset at UTC midnight. 0 = unlimited. */
    maxRequestsPerPlayerPerDay: number;
    /** Whether mc_command is offered to the model. */
    allowCommand: boolean;
    /** Requests running at once across all players. */
    maxConcurrent: number;
    /** User/assistant exchanges remembered per player. */
    historyTurns: number;
    historyTtlMinutes: number;
    imageDetail: "low" | "high" | "auto";
    /** Extra text appended to the built-in system prompt, if AI_SYSTEM_PROMPT_FILE was set. */
    systemPromptExtra?: string;
    requestTimeoutMs: number;

    /** Path of the JSON file persisting per-player usage, limit overrides and the pause flag. */
    usageFile: string;
    /** USD per 1M uncached input tokens, at peak price. */
    priceInput: number;
    /** USD per 1M cached input tokens, at peak price. */
    priceCachedInput: number;
    /** USD per 1M output tokens, at peak price. */
    priceOutput: number;
    /** Label only: "USD" shows as "$", anything else is shown as "<code> " prefix. */
    currency: string;
    /** Per-player daily token cap (input + cached + output), env-level default. 0 = unlimited. */
    maxTokensPerPlayerPerDay: number;
    /** Per-player daily cost cap in `currency`, env-level default. 0 = unlimited. */
    maxCostPerPlayerPerDay: number;
    /** Parsed AI_PEAK_HOURS: the UTC windows in which AI_PRICE_* apply at full price. */
    peakHours: PeakSchedule;
    /** Price multiplier applied outside `peakHours`. */
    offPeakMultiplier: number;
}

function readRequired(name: string): string {
    const value = process.env[name];
    if (value === undefined || value.trim() === "") {
        throw new ConfigError(`missing required environment variable ${name}`);
    }
    return value;
}

function readPositiveInt(name: string, def: number): number {
    const raw = process.env[name];
    if (raw === undefined || raw.trim() === "") return def;
    const parsed = Number(raw);
    if (!Number.isInteger(parsed) || parsed <= 0) {
        throw new ConfigError(`${name} must be a positive integer (got "${raw}")`);
    }
    return parsed;
}

function readNonNegativeInt(name: string, def: number): number {
    const raw = process.env[name];
    if (raw === undefined || raw.trim() === "") return def;
    const parsed = Number(raw);
    if (!Number.isInteger(parsed) || parsed < 0) {
        throw new ConfigError(`${name} must be a non-negative integer (got "${raw}")`);
    }
    return parsed;
}

function readNonNegativeFloat(name: string, def: number): number {
    const raw = process.env[name];
    if (raw === undefined || raw.trim() === "") return def;
    const parsed = Number(raw);
    if (!Number.isFinite(parsed) || parsed < 0) {
        throw new ConfigError(`${name} must be a non-negative number (got "${raw}")`);
    }
    return parsed;
}

function readBoolFlag(name: string, def: boolean): boolean {
    const raw = process.env[name];
    if (raw === undefined || raw.trim() === "") return def;
    if (raw === "1") return true;
    if (raw === "0") return false;
    throw new ConfigError(`${name} must be "0" or "1" (got "${raw}")`);
}

/**
 * Loads the `--agent` mode settings. Throws {@link ConfigError} with a
 * message naming the offending variable; `AI_API_KEY` is the only required
 * one. See {@link usageText} in `config.ts` for the one-screen description.
 */
export function loadAgentConfig(): AgentConfig {
    const baseUrlRaw = process.env.AI_BASE_URL?.trim() || "https://api.deepseek.com";
    const baseUrl = baseUrlRaw.replace(/\/+$/, "");

    const apiKey = readRequired("AI_API_KEY");
    const model = process.env.AI_MODEL?.trim() || "deepseek-flash";

    const maxToolCalls = readPositiveInt("AI_MAX_TOOL_CALLS", 25);
    const maxRequestsPerPlayerPerDay = readNonNegativeInt("AI_MAX_REQUESTS_PER_PLAYER_PER_DAY", 40);
    const allowCommand = readBoolFlag("AI_ALLOW_COMMAND", false);
    const maxConcurrent = readPositiveInt("AI_MAX_CONCURRENT", 2);
    const historyTurns = readPositiveInt("AI_HISTORY_TURNS", 6);
    const historyTtlMinutes = readPositiveInt("AI_HISTORY_TTL_MINUTES", 30);

    const imageDetailRaw = process.env.AI_IMAGE_DETAIL?.trim() || "high";
    if (imageDetailRaw !== "low" && imageDetailRaw !== "high" && imageDetailRaw !== "auto") {
        throw new ConfigError(`AI_IMAGE_DETAIL must be "low", "high" or "auto" (got "${imageDetailRaw}")`);
    }

    let systemPromptExtra: string | undefined;
    const promptFile = process.env.AI_SYSTEM_PROMPT_FILE?.trim();
    if (promptFile) {
        try {
            systemPromptExtra = readFileSync(promptFile, "utf8");
        } catch (err) {
            throw new ConfigError(`AI_SYSTEM_PROMPT_FILE could not be read: ${(err as Error).message}`);
        }
    }

    const requestTimeoutMs = readPositiveInt("AI_REQUEST_TIMEOUT_MS", 120_000);

    const usageFile = process.env.AI_USAGE_FILE?.trim() || "./ashlar-usage.json";
    const priceInput = readNonNegativeFloat("AI_PRICE_INPUT", 0.3);
    const priceCachedInput = readNonNegativeFloat("AI_PRICE_CACHED_INPUT", 0.006);
    const priceOutput = readNonNegativeFloat("AI_PRICE_OUTPUT", 1.2);
    const currency = process.env.AI_CURRENCY?.trim() || "USD";
    const maxTokensPerPlayerPerDay = readNonNegativeInt("AI_MAX_TOKENS_PER_PLAYER_PER_DAY", 0);
    const maxCostPerPlayerPerDay = readNonNegativeFloat("AI_MAX_COST_PER_PLAYER_PER_DAY", 0);
    const offPeakMultiplier = readNonNegativeFloat("AI_OFF_PEAK_MULTIPLIER", 0.5);

    const peakHoursRaw = process.env.AI_PEAK_HOURS?.trim() || "mon-fri 01:00-04:00,06:00-10:00";
    let peakHours: PeakSchedule;
    try {
        peakHours = parsePeakHours(peakHoursRaw);
    } catch (err) {
        throw new ConfigError(`AI_PEAK_HOURS is invalid: ${(err as Error).message}`);
    }

    return {
        baseUrl,
        apiKey,
        model,
        maxToolCalls,
        maxRequestsPerPlayerPerDay,
        allowCommand,
        maxConcurrent,
        historyTurns,
        historyTtlMinutes,
        imageDetail: imageDetailRaw,
        systemPromptExtra,
        requestTimeoutMs,
        usageFile,
        priceInput,
        priceCachedInput,
        priceOutput,
        currency,
        maxTokensPerPlayerPerDay,
        maxCostPerPlayerPerDay,
        peakHours,
        offPeakMultiplier
    };
}
