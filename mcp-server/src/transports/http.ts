// SPDX-License-Identifier: AGPL-3.0-or-later

import { timingSafeEqual } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";

import { createMcpHandler } from "@modelcontextprotocol/server";
import {
    hostHeaderValidation,
    localhostHostValidation,
    localhostOriginValidation,
    originValidation,
    toNodeHandler
} from "@modelcontextprotocol/node";

import type { HttpServeConfig } from "../config.js";
import type { PluginClient } from "../plugin-client.js";
import { buildServer } from "../server.js";

const MCP_PATH = "/mcp";
const HEALTHZ_PATH = "/healthz";

function timingSafeEqualStr(a: string, b: string): boolean {
    const bufA = Buffer.from(a, "utf8");
    const bufB = Buffer.from(b, "utf8");
    if (bufA.length !== bufB.length) return false;
    return timingSafeEqual(bufA, bufB);
}

/** Compares the Authorization header against "Bearer <token>" with a constant-time comparison; writes 401 on mismatch. */
function checkBearer(req: IncomingMessage, res: ServerResponse, token: string): boolean {
    const header = req.headers.authorization;
    const expected = `Bearer ${token}`;
    if (typeof header !== "string" || !timingSafeEqualStr(header, expected)) {
        res.writeHead(401, { "content-type": "application/json" });
        res.end(JSON.stringify({ error: "unauthorized" }));
        return false;
    }
    return true;
}

/** Matches the token-in-path form `/mcp/<token>` and captures the last segment. */
const PATH_TOKEN_PATTERN = /^\/mcp\/([^/]+)$/;

/**
 * Compares the last path segment of a `/mcp/<token>` URL against the
 * configured token with a constant-time comparison; writes 401 on mismatch.
 * This exists for MCP clients that cannot attach custom headers (for
 * example, a remote connector configured with only a URL, such as Claude
 * Desktop's remote MCP connector setup) - they authenticate by putting the
 * token in the URL itself instead of an Authorization header.
 */
function checkPathToken(candidate: string, res: ServerResponse, token: string): boolean {
    if (!timingSafeEqualStr(candidate, token)) {
        res.writeHead(401, { "content-type": "application/json" });
        res.end(JSON.stringify({ error: "unauthorized" }));
        return false;
    }
    return true;
}

export interface HttpServerHandle {
    close(): Promise<void>;
}

/**
 * Serves MCP over Streamable HTTP on a plain node:http server (plan section
 * 4.1): a token check (either an `Authorization: Bearer` header on `/mcp`,
 * or the token as the last path segment of `/mcp/<token>` - plan section
 * 4.5, for clients that cannot set custom headers), then Host header
 * validation, then Origin header validation; anything else (other than the
 * unauthenticated `GET /healthz`) is 404. The SDK's `requireBearerAuth`
 * targets OAuth resource servers and is heavier than a static shared-secret
 * check needs, so the token checks here are hand rolled with
 * `crypto.timingSafeEqual`.
 */
export function startHttp(client: PluginClient, config: HttpServeConfig): HttpServerHandle {
    const handler = createMcpHandler(() => buildServer(client), { responseMode: "json" });
    const nodeHandler = toNodeHandler(handler);

    const validateHost =
        config.allowedHosts.length > 0 ? hostHeaderValidation(config.allowedHosts) : localhostHostValidation();
    const validateOrigin =
        config.allowedHosts.length > 0 ? originValidation(config.allowedHosts) : localhostOriginValidation();

    const httpServer = createServer((req, res) => {
        const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);

        if (req.method === "GET" && url.pathname === HEALTHZ_PATH) {
            res.writeHead(200, { "content-type": "application/json" });
            res.end(JSON.stringify({ ok: true, pluginConnected: client.isConnected() }));
            return;
        }

        let authorizedViaPathToken = false;

        if (url.pathname === MCP_PATH) {
            if (!checkBearer(req, res, config.httpToken)) return;
        } else {
            const pathTokenMatch = PATH_TOKEN_PATTERN.exec(url.pathname);
            if (!pathTokenMatch) {
                res.writeHead(404, { "content-type": "application/json" });
                res.end(JSON.stringify({ error: "not found" }));
                return;
            }
            if (!checkPathToken(pathTokenMatch[1]!, res, config.httpToken)) return;
            authorizedViaPathToken = true;
        }

        if (!validateHost(req, res)) return;
        if (!validateOrigin(req, res)) return;

        if (authorizedViaPathToken) {
            // Rewrite to the canonical /mcp path before handing off: the SDK
            // handler routes purely on pathname and knows nothing about the
            // token-in-path convention.
            req.url = MCP_PATH + url.search;
        }

        void nodeHandler(req, res);
    });

    httpServer.listen(config.port, config.host, () => {
        console.error(`ashlar-mcp[${process.pid}]: serving over HTTP on http://${config.host}:${config.port}${MCP_PATH}`);
    });

    return {
        async close() {
            await handler.close();
            await new Promise<void>((resolve, reject) => {
                httpServer.close(err => (err ? reject(err) : resolve()));
            });
        }
    };
}
