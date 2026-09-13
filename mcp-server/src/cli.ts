#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ConfigError, loadHttpServeConfig, loadPluginConnectionConfig, usageText } from "./config.js";
import { PluginClient } from "./plugin-client.js";
import { startHttp, type HttpServerHandle } from "./transports/http.js";
import { startStdio } from "./transports/stdio.js";

function parseMode(argv: string[]): "stdio" | "http" {
    if (argv.includes("--http")) return "http";
    return "stdio"; // --stdio, or no flag: default
}

async function main(): Promise<void> {
    const mode = parseMode(process.argv.slice(2));

    let pluginConfig;
    try {
        pluginConfig = loadPluginConnectionConfig();
    } catch (err) {
        if (err instanceof ConfigError) {
            console.error(err.message);
            console.error("");
            console.error(usageText());
            process.exit(2);
        }
        throw err;
    }

    const client = new PluginClient({
        url: pluginConfig.pluginUrl,
        token: pluginConfig.pluginToken,
        defaultTimeoutMs: pluginConfig.requestTimeoutMs
    });
    client.start();

    let stopTransport: () => Promise<void>;

    if (mode === "http") {
        let httpConfig;
        try {
            httpConfig = loadHttpServeConfig();
        } catch (err) {
            if (err instanceof ConfigError) {
                console.error(err.message);
                console.error("");
                console.error(usageText());
                process.exit(2);
            }
            throw err;
        }
        const handle: HttpServerHandle = startHttp(client, httpConfig);
        stopTransport = () => handle.close();
    } else {
        const handle = startStdio(client);
        stopTransport = () => handle.close();
    }

    let shuttingDown = false;
    const shutdown = (signal: string) => {
        if (shuttingDown) return;
        shuttingDown = true;
        console.error(`mc-ai-builder-mcp: received ${signal}, shutting down`);
        void stopTransport()
            .catch(err => console.error(`mc-ai-builder-mcp: error during shutdown: ${(err as Error).message}`))
            .finally(() => {
                client.close();
                process.exit(0);
            });
    };
    process.on("SIGINT", () => shutdown("SIGINT"));
    process.on("SIGTERM", () => shutdown("SIGTERM"));
}

main().catch(err => {
    console.error(`mc-ai-builder-mcp: fatal error: ${(err as Error).stack ?? err}`);
    process.exit(1);
});
