// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.command.AllowList;
import net.rcwalter.ashlar.command.AshlarCommand;
import net.rcwalter.ashlar.command.AshlarTabCompleter;
import net.rcwalter.ashlar.command.Cooldown;
import net.rcwalter.ashlar.config.ConfigException;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.engine.FillService;
import net.rcwalter.ashlar.engine.HealthService;
import net.rcwalter.ashlar.engine.HeightmapService;
import net.rcwalter.ashlar.engine.ReadRegionService;
import net.rcwalter.ashlar.engine.RenderService;
import net.rcwalter.ashlar.engine.SnapshotService;
import net.rcwalter.ashlar.engine.SparseService;
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
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
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

        // agent.everyone-can-use (step6.6): the plugin.yml default for
        // ashlar.use is "op"; when the operator opts in, flip the *runtime*
        // default to "everyone" instead so it works with no permissions
        // plugin installed. Daily limits and the cooldown still apply
        // either way - this only controls who may send a request at all.
        Permission usePermission = getServer().getPluginManager().getPermission("ashlar.use");
        if (usePermission != null) {
            usePermission.setDefault(config.agent().everyoneCanUse() ? PermissionDefault.TRUE : PermissionDefault.OP);
            getServer().getPluginManager().recalculatePermissionDefaults(usePermission);
        }

        Path dataFolder = getDataFolder().toPath();
        this.operationLog = new OperationLog(dataFolder, config.logging().logOperations(), getLogger());

        this.executor = new TickBudgetExecutor(this, config, getLogger());

        this.snapshotStore = new SnapshotStore(dataFolder, config.snapshot().maxSnapshots(), getLogger());
        snapshotStore.loadFromDisk();

        // Dedicated single thread for render's image work (ImageRenderer + PNG
        // encoding, step4e-prompt.md): never the main thread, and kept separate
        // from RpcDispatcher's responseExecutor so a slow render cannot delay
        // every other RPC's response delivery.
        this.renderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ashlar-render");
            t.setDaemon(true);
            return t;
        });

        // Execution paths (plan.md step7): independent of the transport, so the
        // in-process tool layer/agent can call them directly in a later step.
        // Handlers below only parse/validate params and delegate to these.
        FillService fillService = new FillService(config, executor);
        SparseService sparseService = new SparseService(config, executor);
        HeightmapService heightmapService = new HeightmapService(executor);
        ReadRegionService readRegionService = new ReadRegionService(executor);
        RenderService renderService = new RenderService(executor, renderExecutor);
        SnapshotService snapshotService = new SnapshotService(config, executor, snapshotStore);
        HealthService healthService = new HealthService(this, startedAt, executor);

        this.dispatcher = new RpcDispatcher(operationLog, getLogger());
        // Re-sending "auth" once already authenticated is idempotent (plan.md 1.2).
        dispatcher.register("auth", (ctx, params) -> {
            JsonObject result = new JsonObject();
            result.addProperty("authenticated", true);
            return CompletableFuture.<JsonElement>completedFuture(result);
        });
        dispatcher.register("health", new HealthHandler(healthService));
        dispatcher.register("fill_batch", new FillBatchHandler(config, fillService));
        dispatcher.register("set_blocks", new SetBlocksHandler(config, sparseService));
        dispatcher.register("heightmap", new HeightmapHandler(config, heightmapService));
        dispatcher.register("read_region", new ReadRegionHandler(config, readRegionService));
        SnapshotHandler snapshotHandler = new SnapshotHandler(config, snapshotService, snapshotStore);
        dispatcher.register("snapshot", snapshotHandler.snapshot());
        dispatcher.register("restore", snapshotHandler.restore());
        dispatcher.register("list_snapshots", snapshotHandler.listSnapshots());
        dispatcher.register("run_command", new RunCommandHandler(config));
        dispatcher.register("players", new PlayersHandler());
        dispatcher.register("subscribe", new SubscribeHandler());
        dispatcher.register("send_message", new SendMessageHandler(config));
        dispatcher.register("render", new RenderHandler(config, renderService));

        InetSocketAddress address = new InetSocketAddress(config.server().host(), config.server().port());
        this.wsServer = new WsServer(address, config, dispatcher, getLogger());
        // Wired in after the WsServer exists (construction-order workaround: the
        // WsServer constructor needs the dispatcher, and therefore every handler,
        // already built), so InvocationContexts built per-request can send progress
        // events (net.SessionProgressSink, plan.md step7).
        this.dispatcher.setWsServer(wsServer);
        this.executor.start();
        this.wsServer.start();

        Cooldown cooldown = new Cooldown(config.agent().cooldownSeconds() * 1000L, System::currentTimeMillis);
        AllowList allowList = new AllowList(dataFolder.resolve("allowed-players.yml"), getLogger());
        allowList.load();
        getCommand("ashlar").setExecutor(new AshlarCommand(config, wsServer, cooldown, allowList));
        getCommand("ashlar").setTabCompleter(new AshlarTabCompleter(allowList));

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
