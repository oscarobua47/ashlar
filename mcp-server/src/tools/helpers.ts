// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * One MCP result content block, exactly as the plugin's `tool_call` RPC
 * sends it (mirrors the plugin's `cc.wujm.ashlar.tool.ContentBlock`).
 * The generic forwarding handler in server.ts passes these through
 * untouched; only the two variants the nine mc_* tools actually return.
 */
export type ContentBlock = { type: "text"; text: string } | { type: "image"; data: string; mimeType: string };
