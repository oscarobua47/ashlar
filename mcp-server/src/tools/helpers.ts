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
export async function runTool(client: PluginClient, body: () => Promise<string>): Promise<ToolTextResult> {
    try {
        const text = await body();
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
    body: () => Promise<ContentBlock[]>
): Promise<ToolContentResult> {
    try {
        const content = await body();
        return { content };
    } catch (err) {
        if (err instanceof PluginError) {
            return { content: [{ type: "text", text: formatPluginError(err, client.pluginUrl) }], isError: true };
        }
        const message = err instanceof Error ? err.message : String(err);
        return { content: [{ type: "text", text: message }], isError: true };
    }
}
