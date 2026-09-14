#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// OBS overlay for MC AI Builder tool calls. Tails the JSONL file written by
// the MCP server when MC_USAGE_LOG is set and serves a transparent web page
// showing the running token total and the latest calls. Zero dependencies.
//
//   MC_USAGE_LOG=~/mc-usage.jsonl   (set on the MCP server, e.g. in claude_desktop_config.json env)
//   node tools/overlay.mjs --file ~/mc-usage.jsonl [--port 4545] [--reset]
//
// Then add an OBS "Browser Source" with URL http://127.0.0.1:4545/ (width ~520,
// height ~260, "Shutdown source when not visible" off). The page background is
// transparent. Open http://127.0.0.1:4545/?reset=1 once to zero the totals
// for a new take (only entries after that moment are counted).

import { createServer } from "node:http";
import { existsSync, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";

const args = process.argv.slice(2);
function opt(name, fallback) {
    const i = args.indexOf(name);
    return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}
const file = opt("--file", process.env.MC_USAGE_LOG ?? `${homedir()}/mc-usage.jsonl`).replace(/^~/, homedir());
const port = Number(opt("--port", "4545"));
let sinceTs = args.includes("--reset") ? new Date().toISOString() : "";

function readEntries() {
    if (!existsSync(file)) return [];
    const lines = readFileSync(file, "utf8").split("\n").filter(Boolean);
    const entries = [];
    for (const line of lines) {
        try {
            const e = JSON.parse(line);
            if (e && typeof e.tool === "string" && (!sinceTs || e.ts > sinceTs)) entries.push(e);
        } catch {
            // ignore partial lines
        }
    }
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
<html><head><meta charset="utf-8"><title>MC AI Builder usage</title>
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
  <div class="title">MC AI Builder &middot; MCP tool calls</div>
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
    if (url.searchParams.get("reset") === "1") {
        sinceTs = new Date().toISOString();
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
