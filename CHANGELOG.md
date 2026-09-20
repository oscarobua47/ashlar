# Changelog

All notable changes to this project are documented in this file.

## Unreleased

- Java package renamed from `net.rcwalter.ashlar` to `cc.wujm.ashlar` (Gradle group `cc.wujm`). No user-visible change: the plugin name, data folder (`plugins/Ashlar/`), config, snapshots and permission nodes are unaffected - drop in the new jar.

## 0.4.7

- New top-level `language` config key (`en`/`zh_CN`/`auto`, default `en`): everything the plugin itself says in chat - `/ashlar` usage/help lines, permission/cooldown/"not configured" messages, progress lines, the usage footer, and every `/ashlar usage`/`limit`/`credit`/`pause`/`resume`/`cancel` reply - can now be shown in Simplified Chinese instead of English. `auto` follows each player's own client language (falling back to English for anything not shipped) with the console always in English; `ashlar simulate` uses the configured language like a player would. The AI's own replies were already following the player's request language and are unaffected; logs, RPC errors, tool descriptions/results and config.yml comments stay English-only. Ships a Chinese README (`README.zh-CN.md`, linked from the top of both READMEs).

## 0.4.6

- `mc_inspect` gains `format: "columns"`: an exact bottom-to-top block-run list for every column in the region, one line per column (`x,z: y1-y2 block | y3-y4 block | ...`, ids without the `minecraft:` prefix), capped at 1024 columns and exclusive with `slice`. Lets the in-game assistant read a damaged area (a crater, a hole, a gap in a wall) in one call instead of one `mc_inspect` slice per layer plus repeated single-column probes.
- Assistant prompt: repairing terrain now calls for exactly one read over the damaged area (`mc_survey` with `matrix: true` for surface-only damage, `mc_inspect format: "columns"` when there may be overhangs or the damage is inside a structure) followed by one `mc_build`, instead of inspecting layer by layer or widening the search area.
- Paper 26.3 support (tested on build 5). The plugin loaded fine but crashed the server on the first read: 26.3's alpha builds run the vanilla chunk system, which throws when a chunk ticket is removed in the same tick the chunk was loaded. Finished tasks now keep their chunk tickets for at least one second (`TickBudgetExecutor.MIN_TICKET_HOLD_TICKS`); 26.2 is unaffected, and the new poplar/wool-stairs/straw-bed blocks need no plugin changes.
- Connection pass no longer knocks down sand: refreshing a fence/pane/wall next to an unsupported gravity block, or next to water that had somewhere to flow, scheduled the physics tick that "no physics" was supposed to avoid - it showed up whenever a player stood nearby (the chunk ticked), and the longer ticket hold above made it show up always. Such a cell is now left unrefreshed (verified on the test server: unsupported sand next to a fence stays; fences next to supported sand still connect).
- Unsupported gravity blocks (sand, gravel, concrete powder, anvils with nothing solid below) are listed in `mc_build`'s support warnings so the model fixes them; `mc_restore` does not report them (natural terrain is full of gravel over cave air).

## 0.4.4

- `/ashlar usage [player|all] <days>` / `/ashlar usage [player|all] <from> <to>`: a per-day usage report (last N days, N in 1-31, or an explicit inclusive UTC date range, also capped at 31 days) alongside the existing today/total summary, for a player or server-wide. A range date may be written as `YYYY-MM-DD`, `YYYYMMDD`, or `MM-DD`/`M-D` (current UTC year); a reversed `from`/`to` is swapped and a future date clamps to today. The caller's own usage with a range needs only `ashlar.use`; naming another player or `all` still needs `ashlar.monitor`. `UsageStore` keeps a rolling 90-day per-day history per player (pruned on save); an old usage file without it gains one day of history, backfilled from its own last-saved "today" on load.

## 0.4.3

- Assistant prompt: a request about an existing build (rebuild it, change the roof, use another material) is anchored on that build's own location, never on where the player stands now; only new things or an explicit "here" use the player's position.
- Locally built jars are versioned `<version>-dev`; only the release workflow produces the bare version number.

## 0.4.2

- Prepaid credit: `/ashlar credit <player> [add|set|off] [amount]` gives operators a way to fund the assistant for a player out of someone else's money rather than the server's own daily budget. Checked before a request starts (like the daily limits) and again after every model turn; when a balance hits zero mid-request the current tool call finishes and the model gets one final, tool-free turn to summarise what was done, what is left, and the snapshot id - the reply gains a line asking the player to get topped up and say "continue". The usage footer and `/ashlar usage` show the balance once it is enabled; players without credit are unaffected.
- `mc_build` gains `liquids: "flow"`: water and lava targets are placed with physics so a single source at the top of a fountain or waterfall actually flows (default `"static"` is unchanged). Capped by `limits.max-flowing-liquids-per-operation` (default 2000). The engine keeps the affected chunks ticking for 10 s after such a build so the fluid spreads even with no player nearby.
- Players' descriptions (`mc_players` and the assistant's request context) include `lookingAt`: the block the player's line of sight hits, its state and the face seen, so "this wall"/"on the house" resolves without searching; the assistant's prompt scales its survey/verify effort to the size of the request.
- Adjacent chests with the same facing written by `mc_build` are paired into double chests (`chestsPaired` in the result); trapped and normal chests never pair with each other.

## 0.4.1

- History between requests keeps only the player's text and the final reply (no tool traffic), keeping follow-up context small; `/ashlar reset` clears it; the system prompt asks the model to make final replies self-sufficient (bounding box, materials, snapshot id).

## 0.4.0

0.3.0 was not published separately; this release includes it. The in-game assistant now runs inside the plugin: `/ashlar` needs no Node process and no inbound port, just a model API key in `config.yml`.

### Plugin (`plugin/`)

- Embedded agent: a pure-Java agent core (`cc.wujm.ashlar.agent`, no Bukkit dependency) ported from the Node `--agent` implementation - `ModelClient` (OpenAI-compatible chat-completions over `java.net.http`, retrying 429/5xx with backoff), `Pricing`/`UsageStore` (peak/off-peak pricing, per-player daily limits, atomic debounced persistence), `HistoryStore` (bounded turns, TTL, image redaction) and `AgentRunner` (the tool-calling loop against the plugin's own `ToolRegistry`).
- `AgentService`: per-player serial request queue plus a global concurrency cap on a virtual-thread executor, progress throttling, per-turn usage accounting (every model call is recorded immediately, including on cancellation or failure), and a cost/token footer on every final reply.
- New `agent:` config keys: `agent.mode` (`embedded`/`external`/`off`, default `embedded`), `agent.model.*` (provider, credentials, tool-call budget, timeouts, image detail, system prompt file, `mc_command` opt-in), `agent.limits.*` (daily caps, concurrency, history), `agent.pricing.*` (peak/off-peak pricing). Usage, limit overrides and the pause flag persist to `plugins/Ashlar/usage.json`.
- `AshlarCommand` calls `AgentService` directly in embedded mode (request, cancel, usage, limit, pause, resume) instead of broadcasting chat events; `external` mode keeps the 0.2 event-broadcast path for a connected integrator process; `off` disables `/ashlar` entirely.
- Console-only `ashlar simulate <x> <y> <z> [facing] <request>`: drives one request through the same code path with a synthetic player position, progress and replies printed to the console - the way to test the assistant without a player online (replaces `tools/agent-sim.mjs`).
- Cancellation now reaches a running fill: `/ashlar cancel` stops between tool calls as before, but `onDisable` cancels every in-flight request and gives the executor up to 5 seconds to drain before shutting down.
- `ChatOut`: the assistant's message-sending logic (progress lines, final replies, monitor echo) is shared between the embedded and external paths instead of living only in the `send_message` RPC handler.

### MCP server (`mcp-server/`)

- `--agent` removed: `src/agent/` (the OpenAI-compatible provider, runner, usage/pricing/history stores, admin commands) is deleted along with `tools/agent-sim.mjs`. Running `ashlar-mcp --agent` now prints a message pointing at `agent.mode: embedded` in the plugin's `config.yml` and exits 2, instead of silently doing nothing useful.
- `@modelcontextprotocol/client` moves back to `devDependencies` - only `tools/e2e.mjs` and the test suite use it now that there is no in-process MCP client driving the agent loop.
- `--help` no longer documents any `AI_*` variable or `--agent` mode.

### Upgrading from 0.2.0

See the README's ["Upgrading from 0.2"](README.md#upgrading-from-02) section under "In-game assistant": stop the old Node-based assistant process, optionally carry over its usage file to `plugins/Ashlar/usage.json`, and move its environment-variable settings into the new `agent.*` keys in `config.yml`.

### Known limitations (tracked for later)

- `mc_inspect` slices merge by block type, not full state, once a slice has more than 47 distinct types.
- Blocks that share a Minecraft map color (e.g. stone/stone bricks/cobblestone) can be indistinguishable in `mc_render`/`mc_survey` images.
- No `/ashlar undo` yet - players have to ask the assistant to restore the snapshot it took.
- An in-progress build cannot be cancelled mid-fill; `/ashlar cancel` now stops at the next tick slice of a running `mc_build` fill rather than only between whole tool calls, but not inside the slice itself.
- Snapshots do not capture block entity contents (sign text, container items); `restore` loses them.

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
