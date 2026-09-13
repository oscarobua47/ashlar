// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.config.ConfigException;
import dev.mcaibuilder.plugin.config.PluginConfig;
import dev.mcaibuilder.plugin.engine.TickBudgetExecutor;
import dev.mcaibuilder.plugin.handler.FillBatchHandler;
import dev.mcaibuilder.plugin.handler.HealthHandler;
import dev.mcaibuilder.plugin.handler.HeightmapHandler;
import dev.mcaibuilder.plugin.handler.PlayersHandler;
import dev.mcaibuilder.plugin.handler.ReadRegionHandler;
import dev.mcaibuilder.plugin.handler.RunCommandHandler;
import dev.mcaibuilder.plugin.handler.SetBlocksHandler;
import dev.mcaibuilder.plugin.handler.SnapshotHandler;
import dev.mcaibuilder.plugin.log.OperationLog;
import dev.mcaibuilder.plugin.net.WsServer;
import dev.mcaibuilder.plugin.rpc.MainThread;
import dev.mcaibuilder.plugin.rpc.RpcDispatcher;
import dev.mcaibuilder.plugin.snapshot.SnapshotStore;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point. Step 1 laid the network/auth/dispatch scaffolding; Step 2
 * added the world-mutating core ({@code fill_batch}/{@code set_blocks});
 * Step 3 rounds out v1 with the read-only and safety-net methods:
 * {@code heightmap}, {@code read_region}, {@code snapshot}/{@code restore}/
 * {@code list_snapshots}, and {@code run_command}. Step 4.5 adds the
 * player-domain read-only method {@code players}.
 */
public final class McAiBuilderPlugin extends JavaPlugin {

    private WsServer wsServer;
    private OperationLog operationLog;
    private RpcDispatcher dispatcher;
    private TickBudgetExecutor executor;
    private SnapshotStore snapshotStore;

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

        // Created before the WsServer, which it needs a reference to for progress
        // events; wired in via setWsServer() once the WsServer exists below, since
        // the WsServer constructor in turn needs the dispatcher (and therefore the
        // fill_batch/set_blocks handlers, and therefore this executor) already built.
        this.executor = new TickBudgetExecutor(this, config, getLogger());

        this.snapshotStore = new SnapshotStore(dataFolder, config.snapshot().maxSnapshots(), getLogger());
        snapshotStore.loadFromDisk();

        this.dispatcher = new RpcDispatcher(operationLog, getLogger());
        // Re-sending "auth" once already authenticated is idempotent (plan.md 1.2).
        dispatcher.register("auth", (session, id, params) -> {
            JsonObject result = new JsonObject();
            result.addProperty("authenticated", true);
            return CompletableFuture.<JsonElement>completedFuture(result);
        });
        dispatcher.register("health", new HealthHandler(this, startedAt, executor));
        dispatcher.register("fill_batch", new FillBatchHandler(config, executor));
        dispatcher.register("set_blocks", new SetBlocksHandler(config, executor));
        dispatcher.register("heightmap", new HeightmapHandler(config, executor));
        dispatcher.register("read_region", new ReadRegionHandler(config, executor));
        SnapshotHandler snapshotHandler = new SnapshotHandler(config, executor, snapshotStore);
        dispatcher.register("snapshot", snapshotHandler.snapshot());
        dispatcher.register("restore", snapshotHandler.restore());
        dispatcher.register("list_snapshots", snapshotHandler.listSnapshots());
        dispatcher.register("run_command", new RunCommandHandler(config));
        dispatcher.register("players", new PlayersHandler());

        InetSocketAddress address = new InetSocketAddress(config.server().host(), config.server().port());
        this.wsServer = new WsServer(address, config, dispatcher, getLogger());
        this.executor.setWsServer(wsServer);
        this.executor.start();
        this.wsServer.start();

        getLogger().info("McAiBuilder v" + getPluginMeta().getVersion() + " enabled. "
                + "WebSocket listening on " + config.server().host() + ":" + config.server().port());
    }

    @Override
    public void onDisable() {
        if (executor != null) {
            executor.shutdown();
        }
        if (wsServer != null) {
            wsServer.shutdown();
        }
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
        if (operationLog != null) {
            operationLog.close();
        }
        if (snapshotStore != null) {
            snapshotStore.shutdown();
        }
    }

    private void logFatal(String message) {
        String bar = "=".repeat(Math.max(60, message.length()));
        getLogger().severe(bar);
        getLogger().severe(message);
        getLogger().severe(bar);
    }
}
