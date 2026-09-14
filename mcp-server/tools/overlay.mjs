#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// OBS overlay for Ashlar tool calls. Zero dependencies.
//
// It tails a log and serves a transparent web page showing the running token
// total and the latest calls. Two log formats are understood:
//   - Claude Desktop's MCP server log (default; the MCP server's stderr lines
//     "[tool <pid>] <name>: <ms> ms, ... = ~<n> tokens" land there with no
//     extra configuration), auto-detected per OS:
//       macOS   ~/Library/Logs/Claude/mcp-server-ashlar.log
//       Windows %APPDATA%/Claude/logs/mcp-server-ashlar.log
//       Linux   ~/.config/Claude/logs/mcp-server-ashlar.log
//   - the JSONL file the MCP server writes when MC_USAGE_LOG is set (use this
//     with Claude Code or any client that does not keep stderr).
//
//   node tools/overlay.mjs [--file <log>] [--port 4545] [--reset]
//
// Then add an OBS "Browser Source" with URL http://127.0.0.1:4545/ (width ~520,
// height ~260, "Shutdown source when not visible" off). The page background is
// transparent. Open http://127.0.0.1:4545/?reset=1 once to zero the totals
// for a new take (only lines appended after that moment are counted).

import { createServer } from "node:http";
import { existsSync, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const args = process.argv.slice(2);
function opt(name, fallback) {
    const i = args.indexOf(name);
    return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}

function defaultLog() {
    if (process.env.MC_USAGE_LOG) return process.env.MC_USAGE_LOG;
    const home = homedir();
    const candidates =
        process.platform === "darwin"
            ? [join(home, "Library", "Logs", "Claude", "mcp-server-ashlar.log")]
            : process.platform === "win32"
              ? [join(process.env.APPDATA ?? join(home, "AppData", "Roaming"), "Claude", "logs", "mcp-server-ashlar.log")]
              : [join(home, ".config", "Claude", "logs", "mcp-server-ashlar.log")];
    return candidates.find(existsSync) ?? candidates[0];
}

const file = opt("--file", defaultLog()).replace(/^~/, homedir());
const port = Number(opt("--port", "4545"));
// Byte offset in the log at the last reset; only lines after it count.
let startOffset = args.includes("--reset") && existsSync(file) ? statSync(file).size : 0;

const STDERR_LINE = /\[tool (\d+)\] (\w+): (\d+) ms, (.*) = ~(\d+) tokens/;

/** Parses one log line in either format into {ts, pid, tool, ms, tokens, detail}, or null. */
function parseLine(line, index) {
    const m = STDERR_LINE.exec(line);
    if (m) {
        return { ts: String(index).padStart(12, "0"), pid: Number(m[1]), tool: m[2], ms: Number(m[3]), tokens: Number(m[5]), detail: m[4] };
    }
    if (line.startsWith("{")) {
        try {
            const e = JSON.parse(line);
            if (e && typeof e.tool === "string") return { ...e, ts: e.ts ?? String(index).padStart(12, "0") };
        } catch {
            // partial line
        }
    }
    return null;
}

function readEntries() {
    if (!existsSync(file)) return [];
    const size = statSync(file).size;
    if (size < startOffset) startOffset = 0; // log rotated or truncated
    const lines = readFileSync(file).subarray(startOffset).toString("utf8").split("\n");
    const entries = [];
    lines.forEach((line, i) => {
        const e = parseLine(line, i);
        if (e) entries.push(e);
    });
    return entries;
}

function summary() {
    const entries = readEntries();
    const total = entries.reduce((s, e) => s + (e.tokens || 0), 0);
    const byTool = {};
    for (const e of entries) byTool[e.tool] = (byTool[e.tool] || 0) + (e.tokens || 0);
    return {
        file,
        mtime: existsSync(file) ? statSync(file).mtimeMs : 0,
        calls: entries.length,
        totalTokens: total,
        byTool,
        recent: entries.slice(-6).reverse()
    };
}

const PAGE = `<!doctype html>
<html><head><meta charset="utf-8"><title>Ashlar usage</title>
<style>
  html, body { margin: 0; background: transparent; font-family: "SF Mono", Menlo, Consolas, monospace; color: #fff; }
  .panel { display: inline-block; min-width: 460px; padding: 14px 18px; border-radius: 14px;
           background: rgba(10, 12, 16, 0.72); box-shadow: 0 2px 12px rgba(0,0,0,.4); }
  .title { font-size: 13px; letter-spacing: .12em; text-transform: uppercase; color: #9ad; opacity: .9; }
  .total { font-size: 44px; font-weight: 700; line-height: 1.1; margin: 6px 0 2px; }
  .total small { font-size: 14px; font-weight: 400; color: #bbb; margin-left: 8px; }
  .meta { font-size: 13px; color: #bbb; margin-bottom: 10px; }
  .recent { font-size: 14px; }
  .row { display: flex; justify-content: space-between; gap: 12px; padding: 3px 0; border-top: 1px solid rgba(255,255,255,.08); }
  .row:first-child { border-top: 0; }
  .row .tool { color: #ffd166; }
  .row.flash { animation: flash 1.2s ease-out; }
  @keyframes flash { from { background: rgba(255,209,102,.35); } to { background: transparent; } }
  .row .tok { color: #8fd; min-width: 90px; text-align: right; }
  .row .ms { color: #999; min-width: 70px; text-align: right; }
</style></head>
<body><div class="panel">
  <div class="title">Ashlar &middot; MCP tool calls</div>
  <div class="total"><span id="total">0</span><small>tokens returned (est.)</small></div>
  <div class="meta"><span id="calls">0</span> calls &middot; last: <span id="last">-</span></div>
  <div class="recent" id="recent"></div>
</div>
<script>
  let lastKey = "";
  async function tick() {
    try {
      const r = await fetch("/data?ts=" + Date.now());
      const d = await r.json();
      document.getElementById("total").textContent = d.totalTokens.toLocaleString();
      document.getElementById("calls").textContent = d.calls;
      const top = d.recent[0];
      document.getElementById("last").textContent = top ? top.tool + " ~" + top.tokens + " tok" : "-";
      const key = top ? top.ts + top.tool : "";
      const flash = key && key !== lastKey;
      lastKey = key;
      document.getElementById("recent").innerHTML = d.recent.map((e, i) =>
        '<div class="row' + (flash && i === 0 ? ' flash' : '') + '"><span class="tool">' + e.tool + '</span>' +
        '<span class="ms">' + e.ms + ' ms</span><span class="tok">~' + e.tokens.toLocaleString() + ' tok</span></div>').join("");
    } catch (e) { /* server restarting */ }
  }
  setInterval(tick, 500); tick();
</script></body></html>`;

createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://127.0.0.1");
    if (url.searchParams.get("reset") === "1" && existsSync(file)) {
        startOffset = statSync(file).size;
    }
    if (url.pathname === "/data") {
        res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
        res.end(JSON.stringify(summary()));
        return;
    }
    res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" });
    res.end(PAGE);
}).listen(port, "127.0.0.1", () => {
    console.log(`overlay: http://127.0.0.1:${port}/  (reading ${file}; add ?reset=1 to zero the totals)`);
});
