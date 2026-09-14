// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Environment configuration for the MC AI Builder MCP server. All values are
 * read once from `process.env` at startup; nothing here mutates it. See
 * {@link usageText} for the one-screen description shown to the user when a
 * required variable is missing.
 */

const LOCALHOST_NAMES = new Set(["127.0.0.1", "localhost", "::1", "[::1]"]);

export interface PluginConnectionConfig {
    /** WebSocket URL of the Paper plugin, e.g. ws://127.0.0.1:8765 */
    pluginUrl: string;
    /** Shared auth token the plugin's config.yml requires on connect. */
    pluginToken: string;
    /** Default per-request timeout in milliseconds, from MC_REQUEST_TIMEOUT_MS. */
    requestTimeoutMs: number;
}

export interface HttpServeConfig {
    host: string;
    port: number;
    /** Bearer token clients must present to reach /mcp. */
    httpToken: string;
    /** Hostnames allowed in the Host/Origin headers when not bound to localhost. */
    allowedHosts: string[];
}

export class ConfigError extends Error {}

function readRequired(name: string): string {
    const value = process.env[name];
    if (value === undefined || value.trim() === "") {
        throw new ConfigError(`missing required environment variable ${name}`);
    }
    return value;
}

/**
 * Loads the plugin-connection settings shared by both stdio and HTTP modes.
 * Throws {@link ConfigError} with a message naming the offending variable.
 */
export function loadPluginConnectionConfig(): PluginConnectionConfig {
    const pluginUrl = readRequired("MC_PLUGIN_URL");
    if (!/^wss?:\/\//.test(pluginUrl)) {
        throw new ConfigError(`MC_PLUGIN_URL must start with ws:// or wss:// (got "${pluginUrl}")`);
    }
    const pluginToken = readRequired("MC_PLUGIN_TOKEN");

    const timeoutRaw = process.env.MC_REQUEST_TIMEOUT_MS;
    let requestTimeoutMs = 600_000;
    if (timeoutRaw !== undefined && timeoutRaw.trim() !== "") {
        const parsed = Number(timeoutRaw);
        if (!Number.isFinite(parsed) || parsed <= 0) {
            throw new ConfigError(`MC_REQUEST_TIMEOUT_MS must be a positive number (got "${timeoutRaw}")`);
        }
        requestTimeoutMs = parsed;
    }

    return { pluginUrl, pluginToken, requestTimeoutMs };
}

/**
 * Loads the HTTP-serving settings. Only called in `--http` mode; stdio mode
 * never touches these variables.
 */
export function loadHttpServeConfig(): HttpServeConfig {
    const host = process.env.MCP_HTTP_HOST?.trim() || "127.0.0.1";

    const portRaw = process.env.MCP_HTTP_PORT;
    let port = 3000;
    if (portRaw !== undefined && portRaw.trim() !== "") {
        const parsed = Number(portRaw);
        if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
            throw new ConfigError(`MCP_HTTP_PORT must be an integer between 1 and 65535 (got "${portRaw}")`);
        }
        port = parsed;
    }

    const httpToken = readRequired("MCP_HTTP_TOKEN");
    if (httpToken.length < 16) {
        throw new ConfigError("MCP_HTTP_TOKEN must be at least 16 characters long");
    }

    const allowedHostsRaw = process.env.MCP_ALLOWED_HOSTS;
    const allowedHosts = (allowedHostsRaw ?? "")
        .split(",")
        .map(h => h.trim())
        .filter(h => h.length > 0);

    if (!LOCALHOST_NAMES.has(host) && allowedHosts.length === 0) {
        throw new ConfigError(
            `MCP_ALLOWED_HOSTS is required when MCP_HTTP_HOST ("${host}") is not localhost/127.0.0.1/::1`
        );
    }

    return { host, port, httpToken, allowedHosts };
}

/** One-screen usage text printed to stderr when a required env var is missing, then the process exits 2. */
export function usageText(): string {
    return `mc-ai-builder-mcp: missing or invalid configuration

Usage:
  mc-ai-builder-mcp --stdio     Serve MCP over stdio (default; for Claude Code/Desktop, Cursor, etc.)
  mc-ai-builder-mcp --http      Serve MCP over Streamable HTTP on MCP_HTTP_HOST:MCP_HTTP_PORT

Environment variables (always required):
  MC_PLUGIN_URL          WebSocket URL of the Paper plugin, e.g. ws://127.0.0.1:8765
  MC_PLUGIN_TOKEN         Auth token configured in the plugin's config.yml (server.token)

Environment variables (optional, always read):
  MC_REQUEST_TIMEOUT_MS   Per-request timeout to the plugin, in milliseconds. Default: 600000
  MC_LOG_USAGE            Set to 0 to stop logging per-call size/token estimates to stderr. Default: on
  MC_USAGE_LOG            Path of a JSONL file to append one line per tool call to (for tools/overlay.mjs). Default: off

Environment variables (--http mode only):
  MCP_HTTP_HOST           Interface to bind. Default: 127.0.0.1
  MCP_HTTP_PORT           Port to bind. Default: 3000
  MCP_HTTP_TOKEN          Bearer token MCP clients must present. Required, >= 16 characters.
  MCP_ALLOWED_HOSTS       Comma-separated hostnames accepted in Host/Origin headers.
                          Required when MCP_HTTP_HOST is not localhost/127.0.0.1/::1.

Example (stdio):
  MC_PLUGIN_URL=ws://127.0.0.1:8765 MC_PLUGIN_TOKEN=changeme mc-ai-builder-mcp --stdio

Example (http):
  MC_PLUGIN_URL=ws://127.0.0.1:8765 MC_PLUGIN_TOKEN=changeme \\
  MCP_HTTP_TOKEN=a-long-random-token-value mc-ai-builder-mcp --http
`;
}
