// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.command.AshlarCommand;
import net.rcwalter.ashlar.command.Cooldown;
import net.rcwalter.ashlar.config.ConfigException;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.engine.TickBudgetExecutor;
import net.rcwalter.ashlar.handler.FillBatchHandler;
import net.rcwalter.ashlar.handler.HealthHandler;
import net.rcwalter.ashlar.handler.HeightmapHandler;
import net.rcwalter.ashlar.handler.PlayersHandler;
import net.rcwalter.ashlar.handler.ReadRegionHandler;
import net.rcwalter.ashlar.handler.RenderHandler;
import net.rcwalter.ashlar.handler.RunCommandHandler;
import net.rcwalter.ashlar.handler.SendMessageHandler;
import net.rcwalter.ashlar.handler.SetBlocksHandler;
import net.rcwalter.ashlar.handler.SnapshotHandler;
import net.rcwalter.ashlar.handler.SubscribeHandler;
import net.rcwalter.ashlar.log.OperationLog;
import net.rcwalter.ashlar.net.WsServer;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcDispatcher;
import net.rcwalter.ashlar.snapshot.SnapshotStore;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point. Step 1 laid the network/auth/dispatch scaffolding; Step 2
 * added the world-mutating core ({@code fill_batch}/{@code set_blocks});
 * Step 3 rounds out v1 with the read-only and safety-net methods:
 * {@code heightmap}, {@code read_region}, {@code snapshot}/{@code restore}/
 * {@code list_snapshots}, and {@code run_command}. Step 4.5 adds the
 * player-domain read-only method {@code players}. Step 4.8 adds {@code
 * render} (region -> PNG).
 */
public final class AshlarPlugin extends JavaPlugin {

    private WsServer wsServer;
    private OperationLog operationLog;
    private RpcDispatcher dispatcher;
    private TickBudgetExecutor executor;
    private SnapshotStore snapshotStore;
    private ExecutorService renderExecutor;

    @Override
    public void onEnable() {
        // Must be set before any AWT class loads (render RPC's BufferedImage/ImageIO
        // use, step4e-prompt.md): most servers run headless with no display/fonts.
        System.setProperty("java.awt.headless", "true");

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
        dispatcher.register("subscribe", new SubscribeHandler());
        dispatcher.register("send_message", new SendMessageHandler());
        // Dedicated single thread for render's image work (ImageRenderer + PNG
        // encoding, step4e-prompt.md): never the main thread, and kept separate
        // from RpcDispatcher's responseExecutor so a slow render cannot delay
        // every other RPC's response delivery.
        this.renderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ashlar-render");
            t.setDaemon(true);
            return t;
        });
        dispatcher.register("render", new RenderHandler(config, executor, renderExecutor));

        InetSocketAddress address = new InetSocketAddress(config.server().host(), config.server().port());
        this.wsServer = new WsServer(address, config, dispatcher, getLogger());
        this.executor.setWsServer(wsServer);
        this.executor.start();
        this.wsServer.start();

        Cooldown cooldown = new Cooldown(config.agent().cooldownSeconds() * 1000L, System::currentTimeMillis);
        getCommand("ashlar").setExecutor(new AshlarCommand(config, wsServer, cooldown));

        getLogger().info("Ashlar v" + getPluginMeta().getVersion() + " enabled. "
                + "WebSocket listening on " + config.server().host() + ":" + config.server().port());
    }

    @Override
    public void onDisable() {
        if (executor != null) {
            executor.shutdown();
        }
        if (renderExecutor != null) {
            renderExecutor.shutdown();
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
