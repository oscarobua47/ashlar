#!/usr/bin/env node
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ConfigError, loadHttpServeConfig, loadPluginConnectionConfig, usageText } from "./config.js";
import { PluginClient } from "./plugin-client.js";
import { fetchCatalog, PluginVersionError, type ToolCatalogResult } from "./server.js";
import { startHttp, type HttpServerHandle } from "./transports/http.js";
import { startStdio } from "./transports/stdio.js";

function parseMode(argv: string[]): "stdio" | "http" {
    if (argv.includes("--http")) return "http";
    return "stdio"; // --stdio, or no flag: default
}

async function main(): Promise<void> {
    const argv = process.argv.slice(2);
    if (argv.includes("--agent")) {
        console.error(
            "the in-game assistant now runs inside the plugin; set agent.mode: embedded in plugins/Ashlar/config.yml and remove --agent"
        );
        process.exit(2);
    }
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

    const client = new PluginClient({
        url: pluginConfig.pluginUrl,
        token: pluginConfig.pluginToken,
        defaultTimeoutMs: pluginConfig.requestTimeoutMs
    });
    client.start();

    let catalog;
    try {
        catalog = await fetchCatalog(client);
    } catch (err) {
        if (err instanceof PluginVersionError) {
            console.error(err.message);
            process.exit(2);
        }
        throw err;
    }
    console.error(
        `ashlar-mcp[${process.pid}]: tool catalog (${catalog.tools.length}): ${catalog.tools.map(spec => spec.name).join(", ")}`
    );
    // Refetch-and-warn only (spec/plan section 4.0): supporting tools/list_changed
    // notifications, i.e. actually re-registering tools on a live connection, is
    // out of scope. Errors here (including UNKNOWN_METHOD, e.g. a downgrade) are
    // logged, not fatal - the process is already up and serving the original catalog.
    client.onReconnect(() => {
        void client
            .request("tool_catalog", {})
            .then(result => {
                const refetched = (result as ToolCatalogResult).tools;
                if (JSON.stringify(refetched) !== JSON.stringify(catalog.tools)) {
                    console.error(
                        `ashlar-mcp[${process.pid}]: the plugin reconnected with a different tool catalog (it may have been upgraded); restart ashlar-mcp to pick up the change`
                    );
                }
            })
            .catch(err => {
                console.error(`ashlar-mcp[${process.pid}]: failed to refetch the tool catalog after reconnect: ${(err as Error).message}`);
            });
    });

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
        const handle: HttpServerHandle = startHttp(client, catalog, httpConfig);
        stopTransport = () => handle.close();
    } else {
        const handle = startStdio(client, catalog);
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
