// SPDX-License-Identifier: AGPL-3.0-or-later

import { appendFileSync } from "node:fs";

import type { ContentBlock } from "./tools/helpers.js";

/**
 * Logs one stderr line per successful tool call with the size of what was
 * returned and a rough token estimate, so users can see what each call costs
 * the model: text at ~4 characters per token, images at width*height/750
 * (the documented Claude image formula). Estimates only. Set MC_LOG_USAGE=0
 * to silence these lines. Called by the generic tool-call forwarding handler
 * in server.ts once per call, whether it succeeded or came back as an
 * `isError` tool result (a transport-level `PluginError` never reaches here).
 */
export function logUsage(name: string, startedAt: number, content: ContentBlock[]): void {
    if (process.env.MC_LOG_USAGE === "0") return;
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
    appendUsageFile({ ts: new Date().toISOString(), pid: process.pid, tool: name, ms: elapsedMs, tokens, detail: parts.join(" + ") });
}

/**
 * When MC_USAGE_LOG names a file, every tool call also appends one JSON line
 * there (`{ts, pid, tool, ms, tokens, detail}`), independent of where the
 * MCP client sends stderr. Consumed by tools/overlay.mjs (an OBS browser
 * source) and handy for spreadsheets. Failures are ignored: logging must
 * never break a tool call.
 */
function appendUsageFile(entry: Record<string, unknown>): void {
    const file = process.env.MC_USAGE_LOG;
    if (!file) return;
    try {
        appendFileSync(file, JSON.stringify(entry) + "\n");
    } catch {
        // ignore
    }
}

/** Reads width/height from a base64 PNG's IHDR chunk without decoding the image. */
function pngDimensions(base64: string): { width: number; height: number } | null {
    const head = Buffer.from(base64.slice(0, 64), "base64");
    if (head.length < 24 || head.readUInt32BE(12) !== 0x49484452) return null; // "IHDR"
    return { width: head.readUInt32BE(16), height: head.readUInt32BE(20) };
}
