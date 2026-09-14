// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Environment configuration for the Ashlar MCP server. All values are
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
    return `ashlar-mcp: missing or invalid configuration

Usage:
  ashlar-mcp --stdio     Serve MCP over stdio (default; for Claude Code/Desktop, Cursor, etc.)
  ashlar-mcp --http      Serve MCP over Streamable HTTP on MCP_HTTP_HOST:MCP_HTTP_PORT
  ashlar-mcp --agent     Run the in-game AI building assistant (answers /ashlar chat requests); no MCP transport
  ashlar-mcp --help      Print this text and exit 0

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

Environment variables (--agent mode only):
  AI_BASE_URL             OpenAI-compatible base URL; "/chat/completions" is appended. Default: https://api.deepseek.com
  AI_API_KEY               Bearer token for the model API. Required.
  AI_MODEL                 Model name. Default: deepseek-flash
  AI_MAX_TOOL_CALLS        Max tool calls per player request before forcing a final answer. Default: 25
  AI_MAX_REQUESTS_PER_PLAYER_PER_DAY  Per-player daily request cap, reset at UTC midnight; 0 = unlimited. Default: 40
  AI_ALLOW_COMMAND         Set to 1 to include mc_command in the agent's tool list. Default: 0
  AI_MAX_CONCURRENT        Requests running at once across all players. Default: 2
  AI_HISTORY_TURNS         User/assistant exchanges remembered per player. Default: 6
  AI_HISTORY_TTL_MINUTES   Idle minutes after which a player's history is dropped. Default: 30
  AI_IMAGE_DETAIL          Image detail passed through on image parts: low/high/auto. Default: high
  AI_SYSTEM_PROMPT_FILE    Optional path to a text file appended to the built-in system prompt.
  AI_REQUEST_TIMEOUT_MS    Per model call timeout, in milliseconds. Default: 120000
  AI_USAGE_FILE            Where per-player usage, limit overrides and the pause flag are persisted. Default: ./ashlar-usage.json
  AI_PRICE_INPUT           USD per 1M uncached input tokens, at peak price. Default: 0.30
  AI_PRICE_CACHED_INPUT    USD per 1M cached input tokens, at peak price. Default: 0.006
  AI_PRICE_OUTPUT          USD per 1M output tokens, at peak price. Default: 1.20
  AI_CURRENCY              Label shown next to costs: "$" for USD, "<code> " prefix otherwise. Default: USD
  AI_MAX_TOKENS_PER_PLAYER_PER_DAY    Per-player daily token cap; 0 = unlimited. Default: 0
  AI_MAX_COST_PER_PLAYER_PER_DAY      Per-player daily cost cap in AI_CURRENCY; 0 = unlimited. Default: 0
  AI_PEAK_HOURS            UTC windows AI_PRICE_* apply at full price; "always" disables the off-peak discount.
                          Default: mon-fri 01:00-04:00,06:00-10:00
  AI_OFF_PEAK_MULTIPLIER   Price multiplier outside AI_PEAK_HOURS. Default: 0.5

Example (stdio):
  MC_PLUGIN_URL=ws://127.0.0.1:8765 MC_PLUGIN_TOKEN=changeme ashlar-mcp --stdio

Example (http):
  MC_PLUGIN_URL=ws://127.0.0.1:8765 MC_PLUGIN_TOKEN=changeme \\
  MCP_HTTP_TOKEN=a-long-random-token-value ashlar-mcp --http

Example (agent):
  MC_PLUGIN_URL=ws://127.0.0.1:8765 MC_PLUGIN_TOKEN=changeme \\
  AI_API_KEY=sk-... ashlar-mcp --agent
`;
}
