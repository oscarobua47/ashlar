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

export interface HttpServerHandle {
    close(): Promise<void>;
}

/**
 * Serves MCP over Streamable HTTP on a plain node:http server (plan section
 * 4.1): bearer-token check, then Host header validation, then Origin header
 * validation, all in front of the single fixed `/mcp` path; anything else
 * (other than the unauthenticated `GET /healthz`) is 404. The SDK's
 * `requireBearerAuth` targets OAuth resource servers and is heavier than a
 * static shared-secret check needs, so the bearer check here is hand
 * rolled with `crypto.timingSafeEqual`.
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

        if (url.pathname !== MCP_PATH) {
            res.writeHead(404, { "content-type": "application/json" });
            res.end(JSON.stringify({ error: "not found" }));
            return;
        }

        if (!checkBearer(req, res, config.httpToken)) return;
        if (!validateHost(req, res)) return;
        if (!validateOrigin(req, res)) return;

        void nodeHandler(req, res);
    });

    httpServer.listen(config.port, config.host, () => {
        console.error(`mc-ai-builder-mcp: serving over HTTP on http://${config.host}:${config.port}${MCP_PATH}`);
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
