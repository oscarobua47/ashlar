// SPDX-License-Identifier: AGPL-3.0-or-later

import { formatPluginError, PluginError } from "../errors.js";
import type { PluginClient } from "../plugin-client.js";

export interface ToolTextResult {
    [key: string]: unknown;
    content: Array<{ type: "text"; text: string }>;
    isError?: boolean;
}

/** A single MCP result content block. Only the variants mc_* tools actually return so far. */
export type ContentBlock = { type: "text"; text: string } | { type: "image"; data: string; mimeType: string };

export interface ToolContentResult {
    [key: string]: unknown;
    content: ContentBlock[];
    isError?: boolean;
}

export function textResult(text: string): ToolTextResult {
    return { content: [{ type: "text", text }] };
}

/**
 * Runs a tool handler body, converting any thrown error into an
 * `isError: true` result with model-actionable text (spec section 4.3 part
 * 4 / plan section 4.4): a {@link PluginError} is formatted with its
 * suggested next step, anything else falls back to its message. Every
 * mc_* tool handler is a thin wrapper around this.
 */
export async function runTool(client: PluginClient, name: string, body: () => Promise<string>): Promise<ToolTextResult> {
    const startedAt = Date.now();
    try {
        const text = await body();
        logUsage(name, startedAt, [{ type: "text", text }]);
        return textResult(text);
    } catch (err) {
        if (err instanceof PluginError) {
            return { content: [{ type: "text", text: formatPluginError(err, client.pluginUrl) }], isError: true };
        }
        const message = err instanceof Error ? err.message : String(err);
        return { content: [{ type: "text", text: message }], isError: true };
    }
}

/**
 * Same error-handling contract as {@link runTool}, but for a handler body
 * that returns a full content-block array (docs/prompts/step4e-prompt.md:
 * mc_render mixes an `image` block with a `text` legend) instead of a
 * single text string.
 */
export async function runToolContent(
    client: PluginClient,
    name: string,
    body: () => Promise<ContentBlock[]>
): Promise<ToolContentResult> {
    const startedAt = Date.now();
    try {
        const content = await body();
        logUsage(name, startedAt, content);
        return { content };
    } catch (err) {
        if (err instanceof PluginError) {
            return { content: [{ type: "text", text: formatPluginError(err, client.pluginUrl) }], isError: true };
        }
        const message = err instanceof Error ? err.message : String(err);
        return { content: [{ type: "text", text: message }], isError: true };
    }
}

/**
 * Logs one stderr line per successful tool call with the size of what was
 * returned and a rough token estimate, so users can see what each call costs
 * the model: text at ~4 characters per token, images at width*height/750
 * (the documented Claude image formula). Estimates only.
 */
function logUsage(name: string, startedAt: number, content: ContentBlock[]): void {
    const parts: string[] = [];
    let tokens = 0;
    for (const block of content) {
        if (block.type === "text") {
            const t = Math.ceil(block.text.length / 4);
            tokens += t;
            parts.push(`${block.text.length} chars (~${t} tokens)`);
        } else if (block.type === "image") {
            const dims = pngDimensions(block.data);
            const t = dims ? Math.ceil((dims.width * dims.height) / 750) : 0;
            tokens += t;
            parts.push(dims ? `image ${dims.width}x${dims.height} (~${t} tokens)` : "image (size unknown)");
        }
    }
    const elapsedMs = Date.now() - startedAt;
    console.error(`[tool ${process.pid}] ${name}: ${elapsedMs} ms, ${parts.join(" + ")} = ~${tokens} tokens`);
}

/** Reads width/height from a base64 PNG's IHDR chunk without decoding the image. */
function pngDimensions(base64: string): { width: number; height: number } | null {
    const head = Buffer.from(base64.slice(0, 64), "base64");
    if (head.length < 24 || head.readUInt32BE(12) !== 0x49484452) return null; // "IHDR"
    return { width: head.readUInt32BE(16), height: head.readUInt32BE(20) };
}
