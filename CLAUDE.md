# Ashlar — notes for Claude Code sessions and contributors

Two components: `plugin/` (Paper 26.2 plugin, Java 25, Gradle) and `mcp-server/` (TypeScript MCP server, MCP SDK v2, Node >= 22). Planning docs, per-step implementation prompts and other private notes live in `docs/private/` (gitignored; Chinese is fine there). Only `docs/images/` is tracked.

## Build and test

```sh
# plugin (JDK 25 required; on this machine it is a keg-only Homebrew install)
cd plugin && JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew build --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew runServer --no-daemon   # local Paper test server in plugin/run (token in plugin/run/plugins/Ashlar/config.yml)

# mcp-server (bypass the local proxy; it throttles registry.npmjs.org)
cd mcp-server && env -u HTTP_PROXY -u HTTPS_PROXY npm install --omit=optional
env -u HTTP_PROXY -u HTTPS_PROXY npm run build && env -u HTTP_PROXY -u HTTPS_PROXY npm test
node tools/e2e.mjs        # end-to-end against the running test server
```

## Hard rules

- English only in code, comments, tool descriptions, logs and errors; sources are pure ASCII (`grep -rnP "[^\x00-\x7F]" src/ --exclude-dir=lang` must be empty; language files under `plugin/src/main/resources/lang/` may contain any script, but Java sources stay pure ASCII - never put a translated string in Java). The public tree (everything tracked by git) is English only, except `README.zh-CN.md`, `lang/*.yml` and the bilingual website under `site/`; private notes go in `docs/private/`.
- Every source file starts with `// SPDX-License-Identifier: AGPL-3.0-or-later`.
- Bukkit/Paper API only on the main thread, and only inside the tick-budgeted executor or `MainThread.call`. Exceptions: `Bukkit.createBlockData`, `Bukkit.getWorld`, loggers.
- Block writes only via `setBlockData(data, <flag>)`; never `setType` or single-argument `setBlockData`. The `true` flag is allowed ONLY for a liquid target (water/lava) under `fill_batch`/`set_blocks`'s `liquids: "flow"`; every other write, and every liquid write when `liquids` is left at its default `"static"`, stays `false`. Connectable blocks get their shape from `ConnectionPass` (air-then-back refresh), which skips gravity blocks, liquids and block entities.
- Validate a request fully on the network thread before enqueueing; use `long` for volumes.
- No NMS, no reflection, no `paper-plugin.yml`, no shading; third-party libs go in `plugin.yml` `libraries:`.
- MCP SDK is v2 (`@modelcontextprotocol/server`, `/node`); do not write v1 `@modelcontextprotocol/sdk` code.
- Tool descriptions use the four-part structure (what / when to use and not / parameters or coordinates / side effects); orientation rules for attached blocks live in the `block` field descriptions of `mc_build`.
- Do not install anything system-wide without asking. Do not kill node processes you did not start (Claude Desktop / Claude Code run `mcp-server/dist/cli.js`).
- Assumptions about engine behaviour (physics, shape updates, heightmap semantics) must be verified on the test server before being implemented.

## Workflow

Opus plans each step in `docs/private/plan.md`, writes `docs/private/prompts/stepN-prompt.md`, delegates implementation to a Sonnet subagent, reviews the result (including generated images) and commits. Commits are per step.
