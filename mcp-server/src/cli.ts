#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later

import { loadAgentConfig } from "./agent/config.js";
import { startAgentService } from "./agent/service.js";
import { ConfigError, loadHttpServeConfig, loadPluginConnectionConfig, usageText } from "./config.js";
import { PluginClient } from "./plugin-client.js";
import { startHttp, type HttpServerHandle } from "./transports/http.js";
import { startStdio } from "./transports/stdio.js";

function parseMode(argv: string[]): "stdio" | "http" | "agent" {
    if (argv.includes("--http")) return "http";
    if (argv.includes("--agent")) return "agent";
    return "stdio"; // --stdio, or no flag: default
}

async function main(): Promise<void> {
    const argv = process.argv.slice(2);
    if (argv.includes("--help") || argv.includes("-h")) {
        process.stdout.write(usageText());
        process.exit(0);
    }

    const mode = parseMode(argv);

    let pluginConfig;
    try {
        pluginConfig = loadPluginConnectionConfig();
    } catch (err) {
        if (err instanceof ConfigError) {
            console.error(`ashlar-mcp[${process.pid}]: ${err.message}`);
            console.error("");
            console.error(usageText());
            process.exit(2);
        }
        throw err;
    }

    let agentConfig;
    if (mode === "agent") {
        try {
            agentConfig = loadAgentConfig();
        } catch (err) {
            if (err instanceof ConfigError) {
                console.error(`ashlar-mcp[${process.pid}]: ${err.message}`);
                console.error("");
                console.error(usageText());
                process.exit(2);
            }
            throw err;
        }
    }

    const client = new PluginClient({
        url: pluginConfig.pluginUrl,
        token: pluginConfig.pluginToken,
        defaultTimeoutMs: pluginConfig.requestTimeoutMs,
        subscribeEvents: mode === "agent" ? ["chat"] : undefined
    });
    client.start();

    let stopTransport: () => Promise<void>;

    if (mode === "http") {
        let httpConfig;
        try {
            httpConfig = loadHttpServeConfig();
        } catch (err) {
            if (err instanceof ConfigError) {
                console.error(`ashlar-mcp[${process.pid}]: ${err.message}`);
                console.error("");
                console.error(usageText());
                process.exit(2);
            }
            throw err;
        }
        const handle: HttpServerHandle = startHttp(client, httpConfig);
        stopTransport = () => handle.close();
    } else if (mode === "agent") {
        const service = await startAgentService(client, agentConfig!);
        console.error(
            `ashlar-mcp[${process.pid}]: agent mode, model ${agentConfig!.model} via ${agentConfig!.baseUrl}, tools: ${service.toolNames.join(", ")}`
        );
        stopTransport = () => {
            service.close();
            return Promise.resolve();
        };
    } else {
        const handle = startStdio(client);
        stopTransport = () => handle.close();
    }

    let shuttingDown = false;
    const shutdown = (signal: string) => {
        if (shuttingDown) return;
        shuttingDown = true;
        console.error(`ashlar-mcp[${process.pid}]: received ${signal}, shutting down`);
        void stopTransport()
            .catch(err => console.error(`ashlar-mcp[${process.pid}]: error during shutdown: ${(err as Error).message}`))
            .finally(() => {
                client.close();
                process.exit(0);
            });
    };
    process.on("SIGINT", () => shutdown("SIGINT"));
    process.on("SIGTERM", () => shutdown("SIGTERM"));
}

main().catch(err => {
    console.error(`ashlar-mcp[${process.pid}]: fatal error: ${(err as Error).stack ?? err}`);
    process.exit(1);
});
