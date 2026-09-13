// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.config.ConfigException;
import dev.mcaibuilder.plugin.config.PluginConfig;
import dev.mcaibuilder.plugin.handler.HealthHandler;
import dev.mcaibuilder.plugin.log.OperationLog;
import dev.mcaibuilder.plugin.net.WsServer;
import dev.mcaibuilder.plugin.rpc.MainThread;
import dev.mcaibuilder.plugin.rpc.RpcDispatcher;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point. Step 1 scope: load and validate config, start the WebSocket
 * server, wire up auth + the {@code health} RPC method. No world-mutating
 * RPC methods yet (those land in Step 2).
 */
public final class McAiBuilderPlugin extends JavaPlugin {

    private WsServer wsServer;
    private OperationLog operationLog;

    @Override
    public void onEnable() {
        Instant startedAt = Instant.now();
        MainThread.init(this);

        saveDefaultConfig();
        reloadConfig();

        PluginConfig config;
        try {
            config = PluginConfig.load(getConfig(), getLogger());
        } catch (ConfigException e) {
            logFatal(e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Path dataFolder = getDataFolder().toPath();
        this.operationLog = new OperationLog(dataFolder, config.logging().logOperations(), getLogger());

        RpcDispatcher dispatcher = new RpcDispatcher(operationLog, getLogger());
        // Re-sending "auth" once already authenticated is idempotent (plan.md 1.2).
        dispatcher.register("auth", (session, params) -> {
            JsonObject result = new JsonObject();
            result.addProperty("authenticated", true);
            return CompletableFuture.<JsonElement>completedFuture(result);
        });
        dispatcher.register("health", new HealthHandler(this, startedAt));

        InetSocketAddress address = new InetSocketAddress(config.server().host(), config.server().port());
        this.wsServer = new WsServer(address, config, dispatcher, getLogger());
        this.wsServer.start();

        getLogger().info("McAiBuilder v" + getPluginMeta().getVersion() + " enabled. "
                + "WebSocket listening on " + config.server().host() + ":" + config.server().port());
    }

    @Override
    public void onDisable() {
        if (wsServer != null) {
            wsServer.shutdown();
        }
        if (operationLog != null) {
            operationLog.close();
        }
    }

    private void logFatal(String message) {
        String bar = "=".repeat(Math.max(60, message.length()));
        getLogger().severe(bar);
        getLogger().severe(message);
        getLogger().severe(bar);
    }
}
