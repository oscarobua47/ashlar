# Changelog

All notable changes to this project are documented in this file.

## 0.3.0

The tool layer moves into the plugin: tool descriptions, JSON schemas and result formatting are now owned by the Java side, and the MCP server becomes a thin protocol adapter.

### Plugin (`plugin/`)

- `InvocationContext` decouples tool execution from the WebSocket session, so tools can run for in-process callers (the in-game assistant) as well as RPC clients; handlers are split into services.
- A main-thread guard protects in-process callers, and `MainThread.call` now completes its futures off the main thread.
- The nine tools (`mc_status`, `mc_players`, `mc_survey`, `mc_render`, `mc_build`, `mc_inspect`, `mc_snapshot`, `mc_restore`, `mc_command`) are Java classes (`tool/mc/`), each with a description and JSON Schema in a resource file (`resources/tools/*.json`) plus a shared `instructions.txt`.
- Model-facing result text formatting (headers, warnings, ASCII maps, error text) is ported to Java (`tool.text`), with cross-language golden files (`plugin/src/test/resources/goldens/`) as the reference.
- Two new RPCs: `tool_catalog` (returns the tool specs plus the `instructions` text) and `tool_call` (runs one tool by name and returns its formatted result).

### MCP server (`mcp-server/`)

- Generic adapter: `ashlar-mcp` no longer contains any Ashlar-specific tool code. On connect it fetches the catalog via `tool_catalog` and registers every tool exactly as the plugin describes it; each call is forwarded to `tool_call` and the result passed through untouched.
- `mcp-server/src/tools/*` (nine tool modules, schemas, result formatting) deleted along with their tests; only a small `ContentBlock` helper remains.
- `ashlar-mcp` 0.3 requires plugin >= 0.3.0: it fetches the catalog at startup and exits with a clear message if the plugin is too old to answer `tool_catalog`. Plugin 0.3 still serves every RPC from 0.1/0.2, so an older `ashlar-mcp` 0.2 keeps working against it.

### Known limitations (tracked for later)

- Snapshots do not capture block entity contents (sign text, container items); `restore` loses them.
- `mc_inspect` slices merge by block type, not full state, once a slice has more than 47 distinct types.
- Blocks that share a Minecraft map color (e.g. stone/stone bricks/cobblestone) can be indistinguishable in `mc_render`/`mc_survey` images.
- No `/ashlar undo` yet - players have to ask the assistant to restore the snapshot it took.
- An in-progress build cannot be cancelled mid-fill; `/ashlar cancel` takes effect between tool calls, not inside one.
- Daily per-player request/token/cost counters and limit overrides are read at `--agent` startup and saved debounced/on close; a hard crash between saves can lose the last few seconds of counters (the file itself, and the pause flag, do survive a clean restart).
- The Node adapter registers the catalog once at startup (no `tools/list_changed`); a plugin reload with changed tool specs needs `ashlar-mcp` restarted to pick them up.

## 0.2.0

In-game AI assistant: players can now ask for a build directly in chat, without any MCP client on their own machine.

### Plugin (`plugin/`)

- `/ashlar <request>` / `/ashlar cancel` command, gated by the `ashlar.use` permission (default op).
- `agent:` config section: `enabled`, `cooldown-seconds`, `max-message-length`, `echo-to-monitors`.
- New RPCs: `subscribe` (lets an `--agent` process receive `chat`/`chat_cancel` events) and `send_message` (the assistant's replies back to a player, `[Ashlar] `-prefixed; takes an optional `kind: "progress"|"final"`).
- Chat and cancel requests are broadcast as events to the subscribed connection; per-player cooldown and message-length checks happen before broadcasting.
- `ashlar.monitor` permission (default op): players with it see a compact echo of every other player's `/ashlar` request and final reply, but none of the progress lines; off switch is `agent.echo-to-monitors`.

- Plugin-local allow list (`/ashlar allow|deny|allowed`, `allowed-players.yml`) and `agent.everyone-can-use`; explicit permission-plugin grants/denials take precedence over the list.

### MCP server (`mcp-server/`)

- `ashlar-mcp --agent`: a new mode with no MCP transport, driving an OpenAI-compatible chat-completions model (DeepSeek by default) through the same nine tools via an in-process tool bridge (no tool code duplication).
- `AI_*` environment variables: provider (`AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL`), limits (`AI_MAX_TOOL_CALLS`, `AI_MAX_REQUESTS_PER_PLAYER_PER_DAY`, `AI_MAX_CONCURRENT`), `AI_ALLOW_COMMAND` to opt `mc_command` into the assistant's tool list, history (`AI_HISTORY_TURNS`, `AI_HISTORY_TTL_MINUTES`), `AI_IMAGE_DETAIL`, `AI_SYSTEM_PROMPT_FILE`, `AI_REQUEST_TIMEOUT_MS`.
- Per-player serial request queue, a global concurrency cap, and a per-player daily request counter.
- Per-player conversation history (bounded turns, idle TTL, old images redacted to keep token cost down).
- Progress lines (`> mc_build from=... to=...`) and the final reply sent back through `send_message`, chunked to fit chat; final chunks are marked `kind: "final"` so the plugin can echo them to monitors.
- System prompt: interior furniture must stay clear of doors and walkways, verified with an `mc_inspect` floor-level slice before replying.
- `--help` now documents `--agent` and its environment variables alongside `--stdio`/`--http`.
- `tools/agent-sim.mjs`: drives one request through the same runner without a player online or a real model call being required to set up, for local testing.
- Per-player usage and cost accounting: every model call's normalised token usage (`agent/provider.ts`, cached tokens from DeepSeek's `prompt_cache_hit_tokens`/OpenAI's `prompt_tokens_details.cached_tokens`) is summed per request (`runRequest` now resolves `{text, usage, toolCalls}`) and priced by `agent/pricing.ts`'s peak/off-peak schedule (`AI_PEAK_HOURS`, `AI_OFF_PEAK_MULTIPLIER`, default matching DeepSeek's own mon-fri 01:00-04:00/06:00-10:00 UTC peak windows at half price off-peak).
- `agent/usage.ts`'s `UsageStore`: persists per-player today/total token and cost counters, per-day limit overrides (cost/tokens/requests, `off` = unlimited) and a global pause flag to `AI_USAGE_FILE` (default `./ashlar-usage.json`), atomically and debounced; `AI_PRICE_INPUT`/`AI_PRICE_CACHED_INPUT`/`AI_PRICE_OUTPUT`/`AI_CURRENCY` price it, `AI_MAX_TOKENS_PER_PLAYER_PER_DAY`/`AI_MAX_COST_PER_PLAYER_PER_DAY` join `AI_MAX_REQUESTS_PER_PLAYER_PER_DAY` as the env-level daily caps.
- Every final reply gets a usage footer, e.g. `(this request: 21.9k tokens, $0.0061 | today: $0.04 of $1.00)`.
- New `ashlar.admin` in-game commands (via the plugin's `admin` chat-subscription event, `agent/admin.ts`'s `createAdminHandler`): `/ashlar usage [player|all]`, `/ashlar limit [player] <cost|tokens|requests> <value|off>` / `... reset`, `/ashlar pause`/`resume`, `/ashlar cancel <player>`.

### Known limitations (tracked for later)

- Snapshots do not capture block entity contents (sign text, container items); `restore` loses them.
- `mc_inspect` slices merge by block type, not full state, once a slice has more than 47 distinct types.
- Blocks that share a Minecraft map color (e.g. stone/stone bricks/cobblestone) can be indistinguishable in `mc_render`/`mc_survey` images.
- No `/ashlar undo` yet - players have to ask the assistant to restore the snapshot it took.
- An in-progress build cannot be cancelled mid-fill; `/ashlar cancel` takes effect between tool calls, not inside one.
- Daily per-player request/token/cost counters and limit overrides are read at `--agent` startup and saved debounced/on close; a hard crash between saves can lose the last few seconds of counters (the file itself, and the pause flag, do survive a clean restart).

## 0.1.0 - v1

First release. A Paper plugin plus a Node MCP server that give an AI client nine tools to survey, render, build, inspect, snapshot/restore and run commands on a live Minecraft server.

### Plugin (`plugin/`, Paper 26.2, Java 25)

- WebSocket RPC server with token authentication (`MessageDigest.isEqual` comparison), an `allowed-ips` allow-list, and an auth timeout on new connections.
- Bulk block engine: `fill_batch` (modes `replace`/`keep`/`outline`/`hollow`/`walls`, optional `filter`) and `set_blocks` for individual placements, including sign text. All writes use `setBlockData(data, false)` (no physics), execute on the main thread inside a per-tick time budget, and force-load their chunks with a plugin chunk ticket for the duration of the operation.
- Limits enforced before any block is touched: `max-blocks-per-operation`, `max-chunks-per-operation`, `max-read-volume`, world allow-list, optional `build-region` bounding box.
- Read-only RPCs: `heightmap` (four Bukkit heightmap types, surface material breakdown), `read_region` (palette + run-length-encoded block data), `render` (PNG: top-down map-style view, four facade views, an exact-color slice, and a color-banded heightmap view with contour lines).
- `players` RPC: online players with block/exact position, yaw/pitch, facing direction, and the block in front of them.
- Snapshot/restore: `snapshot`, `restore`, `list_snapshots`, gzip-persisted to disk, capped and LRU-evicted by `max-snapshots`.
- `run_command`: runs a console command and captures its feedback text; can be disabled in config.
- Post-write shape/support passes: a connection pass so panes/fences/walls/bars/stairs/redstone wire connect to their placed neighbours, and a support-warning check that reports (without fixing) unsupported attached blocks such as ladders, wall torches, signs and carpets - including neighbours of blocks that were just cleared.
- `operations.log` audit trail of every executed RPC.

### MCP server (`mcp-server/`, TypeScript, MCP SDK v2, Node >= 22)

- Nine tools: `mc_status`, `mc_players`, `mc_survey`, `mc_render`, `mc_build`, `mc_inspect`, `mc_snapshot`, `mc_restore`, `mc_command`.
- stdio transport (default) and Streamable HTTP transport (`--http`), with bearer-token auth or a `/mcp/<token>` path form for clients that cannot set headers, plus Host/Origin header validation and an unauthenticated `/healthz`.
- A single long-lived, auto-reconnecting WebSocket client to the plugin (exponential backoff), shared across every MCP connection.
- `mc_survey`/`mc_render` heightmap view render a color-coded PNG with min/max/median height, surface material mix and the largest flat buildable zone; `format: "text"` keeps an ASCII relief map for text-only clients.
- `mc_inspect` decodes the plugin's RLE-encoded region data into block statistics or an ASCII slice with a legend.
- `mc_build` supports auto-snapshotting the affected region before a build and surfaces the plugin's support warnings in its response text.
- Plugin error codes are translated into model-actionable text (e.g. `VOLUME_EXCEEDED` suggests splitting the call).
- `MC_LOG_USAGE` / `MC_USAGE_LOG` per-call token/size accounting, plus a zero-dependency OBS browser-source overlay (`tools/overlay.mjs`).
- End-to-end test script (`tools/e2e.mjs`) driving every tool against a live plugin instance over both transports.

### Known limitations (tracked for later)

- Snapshots do not capture block entity contents (sign text, container items); `restore` loses them.
- `mc_inspect` slices merge by block type, not full state, once a slice has more than 47 distinct types.
- Blocks that share a Minecraft map color (e.g. stone/stone bricks/cobblestone) can be indistinguishable in `mc_render`/`mc_survey` images.
