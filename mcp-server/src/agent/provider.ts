// SPDX-License-Identifier: AGPL-3.0-or-later

import type { AgentConfig } from "./config.js";

/** A content part of a `user` message: plain text, or an image (DeepSeek accepts these only in `user` messages). */
export type ContentPart =
    | { type: "text"; text: string }
    | { type: "image_url"; image_url: { url: string; detail?: "low" | "high" | "auto" } };

export interface ToolCall {
    id: string;
    type: "function";
    function: { name: string; arguments: string };
}

export type ChatMessageRole = "system" | "user" | "assistant" | "tool";

/** The OpenAI-compatible chat message shape used throughout the agent. */
export interface ChatMessage {
    role: ChatMessageRole;
    content?: string | ContentPart[] | null;
    tool_calls?: ToolCall[];
    tool_call_id?: string;
    name?: string;
}

export interface ToolDef {
    type: "function";
    function: { name: string; description: string; parameters: unknown };
}

/** Normalised token usage for one model call. `inputTokens` excludes `cachedInputTokens`. */
export interface CallUsage {
    inputTokens: number;
    cachedInputTokens: number;
    outputTokens: number;
}

export interface ChatCompletionResult {
    message: ChatMessage;
    usage: CallUsage;
    finishReason: string;
}

export interface ChatCompletionOptions {
    messages: ChatMessage[];
    tools: ToolDef[];
    toolChoice?: "none" | "auto";
    signal?: AbortSignal;
}

/** Thrown for a non-2xx response from the model API that isn't retried away. `status` is the HTTP status code. */
export class ProviderError extends Error {
    readonly status: number;

    constructor(status: number, message: string) {
        super(`model API returned ${status}: ${message}`);
        this.name = "ProviderError";
        this.status = status;
    }
}

// 1s, 2s, 4s: up to 3 retries (4 attempts total) on 429/5xx, per docs/prompts/step6b-prompt.md.
const BACKOFF_MS = [1000, 2000, 4000];

function sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
}

/** Parses a `Retry-After` header value (seconds, or an HTTP date) into a millisecond delay, or null if unusable. */
function parseRetryAfterMs(header: string | null): number | null {
    if (!header) return null;
    const seconds = Number(header);
    if (Number.isFinite(seconds) && seconds >= 0) return seconds * 1000;
    const dateMs = Date.parse(header);
    if (!Number.isNaN(dateMs)) {
        const delta = dateMs - Date.now();
        return delta > 0 ? delta : 0;
    }
    return null;
}

/**
 * Calls the configured OpenAI-compatible `/chat/completions` endpoint.
 * Retries on 429/5xx up to 3 times with 1s/2s/4s backoff (honouring
 * `Retry-After` when present); any other non-2xx throws {@link ProviderError}
 * with the status and the first 300 characters of the response body. The
 * request is aborted via `AbortSignal.any([signal, AbortSignal.timeout(...)])`
 * when `signal` is given, so an external cancellation rejects promptly
 * without being retried.
 */
export async function chatCompletion(cfg: AgentConfig, opts: ChatCompletionOptions): Promise<ChatCompletionResult> {
    const url = `${cfg.baseUrl}/chat/completions`;
    const body: Record<string, unknown> = {
        model: cfg.model,
        messages: opts.messages,
        stream: false
    };
    if (opts.tools.length > 0) body.tools = opts.tools;
    if (opts.toolChoice) body.tool_choice = opts.toolChoice;
    const payload = JSON.stringify(body);

    for (let attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
        const timeoutSignal = AbortSignal.timeout(cfg.requestTimeoutMs);
        const signal = opts.signal ? AbortSignal.any([opts.signal, timeoutSignal]) : timeoutSignal;
        const startedAt = Date.now();

        // Not wrapped in try/catch: an abort (external signal or the request
        // timeout) throws here and propagates immediately, without retry.
        const response = await fetch(url, {
            method: "POST",
            headers: { "content-type": "application/json", authorization: `Bearer ${cfg.apiKey}` },
            body: payload,
            signal
        });

        if ((response.status === 429 || response.status >= 500) && attempt < BACKOFF_MS.length) {
            const retryAfterMs = parseRetryAfterMs(response.headers.get("retry-after"));
            await response.text().catch(() => "");
            await sleep(retryAfterMs ?? BACKOFF_MS[attempt]!);
            continue;
        }

        if (!response.ok) {
            const bodyText = await response.text().catch(() => "");
            throw new ProviderError(response.status, bodyText.slice(0, 300));
        }

        const json = (await response.json()) as {
            choices?: Array<{ message: ChatMessage; finish_reason?: string }>;
            usage?: {
                prompt_tokens?: number;
                completion_tokens?: number;
                // DeepSeek's fields.
                prompt_cache_hit_tokens?: number;
                prompt_cache_miss_tokens?: number;
                // OpenAI-style equivalent.
                prompt_tokens_details?: { cached_tokens?: number };
            };
        };
        const choice = json.choices?.[0];
        if (!choice) {
            throw new ProviderError(response.status, "response had no choices[0]");
        }
        const promptTokens = json.usage?.prompt_tokens ?? 0;
        const cachedInputTokens = json.usage?.prompt_cache_hit_tokens ?? json.usage?.prompt_tokens_details?.cached_tokens ?? 0;
        const outputTokens = json.usage?.completion_tokens ?? 0;
        const usage: CallUsage = {
            inputTokens: Math.max(0, promptTokens - cachedInputTokens),
            cachedInputTokens,
            outputTokens
        };
        const elapsedMs = Date.now() - startedAt;
        const hasToolCalls = (choice.message.tool_calls?.length ?? 0) > 0;
        console.error(
            `[agent-provider] model=${cfg.model} prompt_tokens=${promptTokens} cached_tokens=${cachedInputTokens} completion_tokens=${outputTokens} elapsed=${elapsedMs}ms tool_calls=${hasToolCalls}`
        );

        return { message: choice.message, usage, finishReason: choice.finish_reason ?? "" };
    }

    // Unreachable: the loop always returns or throws.
    throw new ProviderError(0, "chatCompletion: exhausted retries without a response");
}
