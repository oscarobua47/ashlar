// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.agent.AgentService;
import cc.wujm.ashlar.agent.HistoryStore;
import cc.wujm.ashlar.agent.ModelClient;
import cc.wujm.ashlar.agent.ModelConfig;
import cc.wujm.ashlar.agent.Pricing;
import cc.wujm.ashlar.agent.UsageStore;
import cc.wujm.ashlar.command.AllowList;
import cc.wujm.ashlar.command.AshlarCommand;
import cc.wujm.ashlar.command.AshlarTabCompleter;
import cc.wujm.ashlar.command.Cooldown;
import cc.wujm.ashlar.config.ConfigException;
import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.i18n.Messages;
import cc.wujm.ashlar.player.ChatOut;
import cc.wujm.ashlar.engine.FillService;
import cc.wujm.ashlar.engine.HealthService;
import cc.wujm.ashlar.engine.HeightmapService;
import cc.wujm.ashlar.engine.ReadRegionService;
import cc.wujm.ashlar.engine.RenderService;
import cc.wujm.ashlar.engine.SnapshotService;
import cc.wujm.ashlar.engine.SparseService;
import cc.wujm.ashlar.engine.TickBudgetExecutor;
import cc.wujm.ashlar.handler.FillBatchHandler;
import cc.wujm.ashlar.handler.HealthHandler;
import cc.wujm.ashlar.handler.HeightmapHandler;
import cc.wujm.ashlar.handler.PlayersHandler;
import cc.wujm.ashlar.handler.ReadRegionHandler;
import cc.wujm.ashlar.handler.RenderHandler;
import cc.wujm.ashlar.handler.RunCommandHandler;
import cc.wujm.ashlar.handler.SendMessageHandler;
import cc.wujm.ashlar.handler.SetBlocksHandler;
import cc.wujm.ashlar.handler.SnapshotHandler;
import cc.wujm.ashlar.handler.SubscribeHandler;
import cc.wujm.ashlar.handler.ToolCallHandler;
import cc.wujm.ashlar.handler.ToolCatalogHandler;
import cc.wujm.ashlar.log.OperationLog;
import cc.wujm.ashlar.net.WsServer;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.rpc.RpcDispatcher;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.snapshot.SnapshotStore;
import cc.wujm.ashlar.tool.ToolRegistry;
import cc.wujm.ashlar.tool.mc.McBuild;
import cc.wujm.ashlar.tool.mc.McCommand;
import cc.wujm.ashlar.tool.mc.McInspect;
import cc.wujm.ashlar.tool.mc.McPlayers;
import cc.wujm.ashlar.tool.mc.McRender;
import cc.wujm.ashlar.tool.mc.McRestore;
import cc.wujm.ashlar.tool.mc.McSnapshot;
import cc.wujm.ashlar.tool.mc.McStatus;
import cc.wujm.ashlar.tool.mc.McSurvey;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private AgentService agentService;

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

        // Handler instances are kept as local variables (rather than passed inline to
        // dispatcher.register) so the same instances can back both the WebSocket RPCs below and
        // the in-process tool layer (plan.md step7.2b): a tool_call for e.g. mc_build calls
        // straight into fillBatchHandler.handle(ctx, params), reusing every validation rule the
        // fill_batch RPC enforces instead of duplicating it.
        HealthHandler healthHandler = new HealthHandler(healthService);
        FillBatchHandler fillBatchHandler = new FillBatchHandler(config, fillService);
        SetBlocksHandler setBlocksHandler = new SetBlocksHandler(config, sparseService);
        HeightmapHandler heightmapHandler = new HeightmapHandler(config, heightmapService);
        ReadRegionHandler readRegionHandler = new ReadRegionHandler(config, readRegionService);
        SnapshotHandler snapshotHandler = new SnapshotHandler(config, snapshotService, snapshotStore);
        RunCommandHandler runCommandHandler = new RunCommandHandler(config);
        PlayersHandler playersHandler = new PlayersHandler();
        RenderHandler renderHandler = new RenderHandler(config, renderService);
        RpcHandler snapshotCreateHandler = snapshotHandler.snapshot();
        RpcHandler restoreHandler = snapshotHandler.restore();
        RpcHandler listSnapshotsHandler = snapshotHandler.listSnapshots();

        this.dispatcher = new RpcDispatcher(operationLog, getLogger());
        // Re-sending "auth" once already authenticated is idempotent (plan.md 1.2).
        dispatcher.register("auth", (ctx, params) -> {
            JsonObject result = new JsonObject();
            result.addProperty("authenticated", true);
            return CompletableFuture.<JsonElement>completedFuture(result);
        });
        dispatcher.register("health", healthHandler);
        dispatcher.register("fill_batch", fillBatchHandler);
        dispatcher.register("set_blocks", setBlocksHandler);
        dispatcher.register("heightmap", heightmapHandler);
        dispatcher.register("read_region", readRegionHandler);
        dispatcher.register("snapshot", snapshotCreateHandler);
        dispatcher.register("restore", restoreHandler);
        dispatcher.register("list_snapshots", listSnapshotsHandler);
        dispatcher.register("run_command", runCommandHandler);
        dispatcher.register("players", playersHandler);
        ChatOut chatOut = new ChatOut(config, getLogger());
        dispatcher.register("subscribe", new SubscribeHandler());
        dispatcher.register("send_message", new SendMessageHandler(chatOut));
        dispatcher.register("render", renderHandler);

        // The nine mc_* tools (plan.md step7.2b), in the same order as mcp-server's
        // tools/index.ts registerAllTools, each backed by the RpcHandler instances above.
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new McStatus(healthHandler),
                new McPlayers(playersHandler),
                new McSurvey(heightmapHandler, renderHandler),
                new McBuild(snapshotCreateHandler, fillBatchHandler, setBlocksHandler),
                new McInspect(readRegionHandler),
                new McRender(renderHandler),
                new McSnapshot(snapshotCreateHandler, listSnapshotsHandler),
                new McRestore(restoreHandler),
                new McCommand(runCommandHandler)));
        dispatcher.register("tool_catalog", new ToolCatalogHandler(toolRegistry));
        dispatcher.register("tool_call", new ToolCallHandler(toolRegistry));

        PluginConfig.AgentConfig.Mode agentMode = config.agent().mode();
        if (agentMode == PluginConfig.AgentConfig.Mode.EMBEDDED) {
            PluginConfig.AgentConfig.ModelConfig modelCfg = config.agent().model();
            if (modelCfg.apiKey().isEmpty()) {
                getLogger().warning("agent.mode is embedded but agent.model.api-key is empty; "
                        + "/ashlar will report the assistant as not configured until it is set.");
            } else {
                ModelClient modelClient = new ModelClient(
                        new ModelConfig(modelCfg.baseUrl(), modelCfg.apiKey(), modelCfg.model(),
                                Duration.ofMillis(modelCfg.requestTimeoutMs())),
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                        getLogger());
                PluginConfig.AgentConfig.PricingConfig pricingCfg = config.agent().pricing();
                UsageStore usageStore = new UsageStore(dataFolder.resolve("usage.json"),
                        pricingCfg.input(), pricingCfg.cachedInput(), pricingCfg.output(), pricingCfg.currency(),
                        new UsageStore.Limits(config.agent().limits().maxCostPerPlayerPerDay(),
                                config.agent().limits().maxTokensPerPlayerPerDay(),
                                config.agent().limits().maxRequestsPerPlayerPerDay()),
                        Pricing.parsePeakHours(pricingCfg.peakHours()), pricingCfg.offPeakMultiplier());
                HistoryStore historyStore = new HistoryStore(config.agent().limits().historyTurns(),
                        config.agent().limits().historyTtlMinutes());
                // Resolves the effective chat language for one admin-reply recipient
                // (step8i-prompt.md): only ever invoked from AdminActions.handle, which is
                // itself only ever called from the main thread (AshlarCommand.onCommand), so
                // this Bukkit call (org.bukkit.entity.Player#locale()) is safe here.
                java.util.function.Function<String, String> languageForUuid = uuid -> {
                    org.bukkit.entity.Player online = getServer().getPlayer(java.util.UUID.fromString(uuid));
                    return online != null
                            ? Messages.forPlayer(config.language(), online.locale())
                            : Messages.forConsole(config.language());
                };
                this.agentService = new AgentService(config.agent(), toolRegistry, modelClient, usageStore,
                        historyStore, chatOut, getLogger(), languageForUuid);
            }
            getLogger().info("Ashlar agent: mode=embedded model=" + modelCfg.model()
                    + " base-url=" + modelCfg.baseUrl()
                    + (this.agentService != null ? " (configured)" : " (NOT configured - see warning above)"));
        } else {
            getLogger().info("Ashlar agent: mode=" + agentMode.name().toLowerCase(java.util.Locale.ROOT));
        }

        if (config.server().enabled()) {
            InetSocketAddress address = new InetSocketAddress(config.server().host(), config.server().port());
            this.wsServer = new WsServer(address, config, dispatcher, getLogger());
            // Wired in after the WsServer exists (construction-order workaround: the
            // WsServer constructor needs the dispatcher, and therefore every handler,
            // already built), so InvocationContexts built per-request can send progress
            // events (net.SessionProgressSink, plan.md step7).
            this.dispatcher.setWsServer(wsServer);
        } else if (this.agentService == null) {
            getLogger().warning("server.enabled is false and the in-game assistant is not configured:"
                    + " this plugin will do nothing until one of them is set up.");
        }
        this.executor.start();
        if (this.wsServer != null) {
            this.wsServer.start();
        }

        Cooldown cooldown = new Cooldown(config.agent().cooldownSeconds() * 1000L, System::currentTimeMillis);
        AllowList allowList = new AllowList(dataFolder.resolve("allowed-players.yml"), getLogger());
        allowList.load();
        getCommand("ashlar").setExecutor(new AshlarCommand(config, wsServer, cooldown, allowList, agentService));
        getCommand("ashlar").setTabCompleter(new AshlarTabCompleter(allowList));

        getLogger().info("Ashlar v" + getPluginMeta().getVersion() + " enabled. "
                + (config.server().enabled()
                        ? "WebSocket listening on " + config.server().host() + ":" + config.server().port()
                        : "WebSocket server disabled (server.enabled: false); /ashlar only."));
    }

    @Override
    public void onDisable() {
        if (agentService != null) {
            agentService.shutdown();
        }
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
