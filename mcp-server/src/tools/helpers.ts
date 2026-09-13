// SPDX-License-Identifier: AGPL-3.0-or-later

import { formatPluginError, PluginError } from "../errors.js";
import type { PluginClient } from "../plugin-client.js";

export interface ToolTextResult {
    [key: string]: unknown;
    content: Array<{ type: "text"; text: string }>;
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
