// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import cc.wujm.ashlar.agent.AdminActions;
import cc.wujm.ashlar.agent.AgentRunner;
import cc.wujm.ashlar.agent.AgentService;
import cc.wujm.ashlar.agent.ConsolePlayer;
import cc.wujm.ashlar.agent.Outbox;
import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.config.ConfigReloader;
import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.config.ReloadOutcome;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.i18n.Messages;
import cc.wujm.ashlar.net.WsServer;
import cc.wujm.ashlar.player.Facing;
import cc.wujm.ashlar.player.Monitors;
import cc.wujm.ashlar.player.PlayerJson;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.snapshot.Snapshot;
import cc.wujm.ashlar.snapshot.SnapshotStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code /ashlar}: the in-game entry point into the AI assistant
 * (step6a-prompt.md) and, from step6e-prompt.md on, its admin surface
 * (usage/limit/credit/cancel &lt;player&gt;/pause/resume/allow/deny/allowed/help), plus {@code reload}
 * (step8j-prompt.md, hot config reload).
 * Bukkit runs command executors on the main thread, so this may read Bukkit
 * state freely; the only network action most subcommands take is
 * {@link WsServer#broadcastEvent}, which just enqueues bytes on the socket -
 * nothing here talks to an LLM or does any accounting, that is a connected
 * {@code ashlar-mcp --agent} process. {@code allow}/{@code deny}/{@code
 * allowed} are the one exception: they only touch the plugin-local
 * {@link AllowList} and never produce an event.
 *
 * <p>Argument parsing lives in the pure, Bukkit-free {@link AshlarArgs} so
 * the grammar is unit-testable; this class does the permission checks,
 * Bukkit player lookups, and builds/sends the resulting event.
 */
public final class AshlarCommand implements CommandExecutor {

    private static final Component PREFIX = Component.text("[Ashlar] ", NamedTextColor.GOLD);

    private final ConfigHolder configHolder;
    private final WsServer wsServer;
    private final Cooldown cooldown;
    private final AllowList allowList;
    private final AgentService agentService;
    private final ConfigReloader reloader;
    private final SnapshotStore snapshotStore;
    private final RpcHandler restoreHandler;
    private final UndoTracker undoTracker;
    private final Outbox chatOut;
    private final AtomicLong requestCounter = new AtomicLong();
    private final Messages messages = Messages.instance();

    /**
     * @param snapshotStore  backs {@code /ashlar undo} (step8l-prompt.md): {@link
     *                       SnapshotStore#forOwnerNewestFirst} finds the caller's own snapshots.
     * @param restoreHandler the same {@code restore} {@link RpcHandler} instance {@code mc_restore}
     *                       calls (registered in {@code AshlarPlugin}), reused so undo gets the
     *                       exact same validation, tick-budgeted executor and connect/support
     *                       passes with no model call in between.
     * @param undoTracker    per-run "already undone" bookkeeping so a second {@code /ashlar undo}
     *                       walks one step further back; see {@link UndoTracker}.
     * @param chatOut        delivers the (possibly async) undo result back to the player, the same
     *                       way {@link AgentService} delivers replies - {@code restoreHandler}
     *                       completes off the main thread.
     */
    public AshlarCommand(ConfigHolder configHolder, WsServer wsServer, Cooldown cooldown, AllowList allowList,
                          AgentService agentService, ConfigReloader reloader, SnapshotStore snapshotStore,
                          RpcHandler restoreHandler, UndoTracker undoTracker, Outbox chatOut) {
        this.configHolder = configHolder;
        this.wsServer = wsServer;
        this.cooldown = cooldown;
        this.allowList = allowList;
        this.agentService = agentService;
        this.reloader = reloader;
        this.snapshotStore = snapshotStore;
        this.restoreHandler = restoreHandler;
        this.undoTracker = undoTracker;
        this.chatOut = chatOut;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean consoleSender = !(sender instanceof Player);
        if (args.length == 0) {
            reply(sender, msg(sender, "command.grammar.top"));
            return true;
        }

        String language = effectiveLanguage(sender);
        AshlarArgs.Parsed parsed = AshlarArgs.parse(args, consoleSender, language);

        if (parsed.kind() == AshlarArgs.Kind.SIMULATE) {
            handleSimulate(sender, parsed.simulate());
            return true;
        }
        if (parsed.kind() == AshlarArgs.Kind.RELOAD) {
            handleReload(sender);
            return true;
        }
        if (consoleSender) {
            if (parsed.kind() == AshlarArgs.Kind.INVALID
                    && (args[0].equalsIgnoreCase("simulate") || args[0].equalsIgnoreCase("reload"))) {
                reply(sender, parsed.error());
            } else {
                reply(sender, msg(sender, "command.player_only"));
            }
            return true;
        }

        Player player = (Player) sender;
        if (!hasAccess(player, args, parsed)) {
            reply(player, msg(player, "command.no_permission"));
            return true;
        }
        if (parsed.kind() == AshlarArgs.Kind.INVALID) {
            reply(player, parsed.error());
            return true;
        }

        PluginConfig.AgentConfig.Mode mode = configHolder.get().agent().mode();
        if (mode == PluginConfig.AgentConfig.Mode.OFF && parsed.kind() != AshlarArgs.Kind.HELP) {
            reply(player, msg(player, "agent.disabled"));
            return true;
        }
        if (mode == PluginConfig.AgentConfig.Mode.EXTERNAL && needsConnection(parsed.kind())
                && !wsServer.hasSubscriber("chat")) {
            reply(player, msg(player, "agent.not_connected"));
            return true;
        }
        if (mode == PluginConfig.AgentConfig.Mode.EMBEDDED && needsConnection(parsed.kind()) && agentService == null) {
            reply(player, msg(player, "agent.not_configured"));
            return true;
        }

        switch (parsed.kind()) {
            case REQUEST -> handleRequest(player, parsed.args());
            case CANCEL_SELF -> handleCancelSelf(player);
            case CANCEL_OTHER -> handleCancelOther(player, parsed.targetName());
            case RESET -> handleReset(player);
            case USAGE_SELF -> handleUsageSelf(player, parsed.args());
            case USAGE_OTHER -> handleUsageOther(player, parsed.targetName(), parsed.args());
            case USAGE_ALL -> handleUsageAll(player, parsed.args());
            case LIMIT -> handleLimit(player, parsed.targetName(), parsed.args());
            case CREDIT_SHOW -> handleCredit(player, parsed.targetName(), List.of());
            case CREDIT_SET -> handleCredit(player, parsed.targetName(), parsed.args());
            case PAUSE -> handlePause(player);
            case RESUME -> handleResume(player);
            case ALLOW -> handleAllow(player, parsed.targetName());
            case DENY -> handleDeny(player, parsed.targetName());
            case ALLOWED -> handleAllowed(player);
            case UNDO -> handleUndo(player);
            case HELP -> handleHelp(player);
            case SIMULATE -> throw new IllegalStateException("handled above");
            case RELOAD -> throw new IllegalStateException("handled above");
            case INVALID -> throw new IllegalStateException("handled above");
        }
        return true;
    }

    // -- simulate (console only) ---------------------------------------

    private void handleSimulate(CommandSender sender, AshlarArgs.Simulate sim) {
        PluginConfig.AgentConfig.Mode mode = configHolder.get().agent().mode();
        if (mode == PluginConfig.AgentConfig.Mode.OFF) {
            reply(sender, msg(sender, "agent.disabled"));
            return;
        }
        if (mode != PluginConfig.AgentConfig.Mode.EMBEDDED) {
            reply(sender, msg(sender, "command.request.simulate_requires_embedded"));
            return;
        }
        if (agentService == null) {
            reply(sender, msg(sender, "agent.not_configured"));
            return;
        }
        int[] pos = {sim.x(), sim.y(), sim.z()};
        int[] offset = Facing.offset(sim.facing());
        int[] inFront = {pos[0] + offset[0], pos[1] + offset[1], pos[2] + offset[2]};
        AgentRunner.PlayerInfo playerInfo = new AgentRunner.PlayerInfo(
                ConsolePlayer.NAME, ConsolePlayer.ID.toString(), configHolder.get().world().defaultWorld(),
                pos, sim.facing(), inFront, "CREATIVE", null, effectiveLanguage(sender));
        agentService.submit(playerInfo, String.join(" ", sim.text()));
    }

    // -- reload (player with ashlar.admin, or console) -----------------

    /**
     * {@code /ashlar reload} (step8j-prompt.md): re-reads {@code config.yml} and applies every hot
     * key immediately; a changed cold key (restart required) is listed in the reply but still takes
     * no effect until the restart. Console may always run it (same trust level as {@code ashlar
     * simulate}); a player needs {@code ashlar.admin}.
     */
    private void handleReload(CommandSender sender) {
        if (sender instanceof Player player && !player.hasPermission(Access.ADMIN)) {
            reply(player, msg(player, "command.no_permission"));
            return;
        }
        ReloadOutcome outcome = reloader.reload();
        if (!outcome.success()) {
            reply(sender, msg(sender, "command.reload.failed", outcome.error()));
            return;
        }
        if (outcome.coldChanges().isEmpty()) {
            reply(sender, msg(sender, "command.reload.done"));
        } else {
            reply(sender, msg(sender, "command.reload.done_needs_restart", String.join(", ", outcome.coldChanges())));
        }
    }

    // -- request -------------------------------------------------------

    private void handleRequest(Player player, List<String> words) {
        String text = String.join(" ", words);
        int maxLength = configHolder.get().agent().maxMessageLength();
        if (text.length() > maxLength) {
            reply(player, msg(player, "command.request.too_long", maxLength));
            return;
        }

        long remainingMillis = cooldown.remainingMillis(player.getUniqueId());
        if (remainingMillis > 0) {
            long remainingSeconds = (remainingMillis + 999) / 1000;
            reply(player, msg(player, "command.request.cooldown", remainingSeconds));
            return;
        }

        cooldown.record(player.getUniqueId());
        echoToMonitors(player, Component.text(player.getName() + " asked: ", NamedTextColor.GRAY)
                .append(Component.text(text, NamedTextColor.WHITE)));

        if (isEmbedded()) {
            agentService.submit(playerInfoOf(player), text);
            return;
        }

        JsonObject event = new JsonObject();
        event.addProperty("event", "chat");
        event.addProperty("requestId", "chat-" + requestCounter.incrementAndGet());
        event.add("player", PlayerJson.describe(player));
        event.addProperty("text", text);
        wsServer.broadcastEvent("chat", event);
        reply(player, msg(player, "command.request.sent"));
    }

    // -- cancel ----------------------------------------------------------

    private void handleCancelSelf(Player player) {
        echoToMonitors(player, Component.text(player.getName() + " cancelled their request", NamedTextColor.GRAY));

        if (isEmbedded()) {
            boolean cancelled = agentService.cancel(player.getUniqueId());
            reply(player, msg(player, cancelled ? "command.cancel.requested" : "command.cancel.nothing"));
            return;
        }

        JsonObject event = new JsonObject();
        event.addProperty("event", "chat_cancel");
        event.add("player", actorJson(player));
        wsServer.broadcastEvent("chat", event);
        reply(player, msg(player, "command.cancel.requested"));
    }

    private void handleCancelOther(Player player, String targetName) {
        if (isEmbedded()) {
            agentService.admin("cancel", byOf(player), targetOf(targetName), List.of());
            return;
        }
        broadcastAdmin(player, "cancel", targetJson(targetName), List.of());
        reply(player, msg(player, "command.cancel.requested_for", targetName));
    }

    // -- usage -------------------------------------------------------------

    private void handleReset(Player player) {
        if (!isEmbedded()) {
            reply(player, msg(player, "command.reset.embedded_only"));
            return;
        }
        boolean had = agentService.reset(player.getUniqueId());
        reply(player, msg(player, had ? "command.reset.done" : "command.reset.nothing"));
    }

    private void handleUsageSelf(Player player, List<String> rangeArgs) {
        if (isEmbedded()) {
            agentService.admin("usage", byOf(player), null, rangeArgs);
            return;
        }
        broadcastAdmin(player, "usage", JsonNull.INSTANCE, rangeArgs);
        reply(player, msg(player, "command.usage.sent"));
    }

    private void handleUsageOther(Player player, String targetName, List<String> rangeArgs) {
        if (isEmbedded()) {
            agentService.admin("usage", byOf(player), targetOf(targetName), rangeArgs);
            return;
        }
        broadcastAdmin(player, "usage", targetJson(targetName), rangeArgs);
        reply(player, msg(player, "command.usage.sent"));
    }

    private void handleUsageAll(Player player, List<String> rangeArgs) {
        if (isEmbedded()) {
            agentService.admin("usage", byOf(player), new AdminActions.Target("all", null), rangeArgs);
            return;
        }
        JsonObject target = new JsonObject();
        target.addProperty("name", "all");
        target.add("uuid", JsonNull.INSTANCE);
        broadcastAdmin(player, "usage", target, rangeArgs);
        reply(player, msg(player, "command.usage.sent"));
    }

    // -- limit / pause / resume ---------------------------------------------

    private void handleLimit(Player player, String targetName, List<String> limitArgs) {
        boolean isDefault = targetName.equalsIgnoreCase("default");
        if (isEmbedded()) {
            agentService.admin("limit", byOf(player), isDefault ? null : targetOf(targetName), limitArgs);
            return;
        }
        broadcastAdmin(player, "limit", isDefault ? JsonNull.INSTANCE : targetJson(targetName), limitArgs);
        reply(player, msg(player, "command.limit.sent"));
    }

    /**
     * {@code /ashlar credit <player>} (show, {@code creditArgs} empty) and {@code /ashlar credit
     * <player> add|set|off [amount]} - embedded mode only; external mode has no local accounting to
     * show or change, so it just says so (step8f-prompt.md).
     */
    private void handleCredit(Player player, String targetName, List<String> creditArgs) {
        if (!isEmbedded()) {
            reply(player, msg(player, "command.credit.external"));
            return;
        }
        if (agentService == null) {
            reply(player, msg(player, "agent.not_configured"));
            return;
        }
        agentService.admin("credit", byOf(player), targetOf(targetName), creditArgs);
    }

    private void handlePause(Player player) {
        if (isEmbedded()) {
            agentService.admin("pause", byOf(player), null, List.of());
            return;
        }
        broadcastAdmin(player, "pause", JsonNull.INSTANCE, List.of());
        reply(player, msg(player, "command.pause.sent"));
    }

    private void handleResume(Player player) {
        if (isEmbedded()) {
            agentService.admin("resume", byOf(player), null, List.of());
            return;
        }
        broadcastAdmin(player, "resume", JsonNull.INSTANCE, List.of());
        reply(player, msg(player, "command.resume.sent"));
    }

    // -- allow / deny / allowed: plugin-local, no event to Node -------------

    private void handleAllow(Player player, String targetName) {
        if (allowList.add(targetName)) {
            reply(player, msg(player, "command.allow.added", targetName));
        } else {
            reply(player, msg(player, "command.allow.already", targetName));
        }
    }

    private void handleDeny(Player player, String targetName) {
        if (allowList.remove(targetName)) {
            reply(player, msg(player, "command.deny.removed", targetName));
        } else {
            reply(player, msg(player, "command.deny.not_found", targetName));
        }
    }

    private void handleAllowed(Player player) {
        List<String> names = allowList.names();
        if (names.isEmpty()) {
            reply(player, msg(player, "command.allowed.empty"));
        } else {
            reply(player, msg(player, "command.allowed.list", String.join(", ", names)));
        }
    }

    // -- undo (step8l-prompt.md): deterministic restore, no model call ------

    /**
     * Restores the newest snapshot owned by {@code player} that has not already been undone this
     * run, through the exact same {@code restoreHandler} {@code mc_restore} calls - same
     * validation, same tick-budgeted executor, same connect/support passes - then marks it undone
     * so a following {@code /ashlar undo} walks one step further back. No model call, no new
     * snapshot: restoring never re-snapshots (step8l-prompt.md &sect;B - "do not let undo pile up
     * snapshots of its own"). Not gated by the embedded/external mode checks above (added to
     * {@link #needsConnection}'s false set): it never talks to a connected Node process, and under
     * a mode with no player-owned snapshots (external/MCP-only use) it naturally reports "nothing
     * to undo" instead.
     */
    private void handleUndo(Player player) {
        runUndo(player.getUniqueId(), player.getName(), player);
    }

    /** Core of {@link #handleUndo}, factored out so it can be driven by any {@link CommandSender}. */
    private void runUndo(UUID uuid, String name, CommandSender sender) {
        String language = effectiveLanguage(sender);
        List<Snapshot> owned = snapshotStore.forOwnerNewestFirst(uuid);
        Optional<Snapshot> target = undoTracker.next(owned);
        if (target.isEmpty()) {
            reply(sender, msg(sender, "command.undo.nothing"));
            return;
        }
        Snapshot snapshot = target.get();

        InvocationContext ctx = InvocationContext.of(
                new InvocationContext.Principal(InvocationContext.Kind.PLAYER, uuid.toString(), name),
                "undo-" + requestCounter.incrementAndGet(),
                (done, total) -> { });
        JsonObject params = new JsonObject();
        params.addProperty("id", snapshot.id());

        // onCommand (this method's only caller) runs on the main thread, but restoreHandler ->
        // SnapshotService.restore asserts it is never called from there (same rule every RPC
        // handler and tool call follows - MainThread.assertNotPrimary). A one-off virtual thread
        // hops off it first, matching how AgentRunner's tool calls are never made from the main
        // thread either; chatOut then hops back before touching the Player API for the reply.
        Thread.ofVirtual().name("ashlar-undo-" + uuid).start(() ->
                restoreHandler.handle(ctx, params).whenComplete((el, throwable) -> {
                    if (throwable != null) {
                        chatOut.send(uuid, messages.get(language, "command.undo.failed", errorMessageOf(throwable)), false);
                        return;
                    }
                    undoTracker.markUndone(snapshot.id());
                    JsonObject r = el.getAsJsonObject();
                    Region region = snapshot.region();
                    String coords = region.minX() + "," + region.minY() + "," + region.minZ()
                            + "-" + region.maxX() + "," + region.maxY() + "," + region.maxZ();
                    chatOut.send(uuid, messages.get(language, "command.undo.done",
                            r.get("restored").getAsLong(), coords, snapshot.id()), false);
                }));
    }

    private static String errorMessageOf(Throwable throwable) {
        Throwable cause = (throwable instanceof CompletionException && throwable.getCause() != null)
                ? throwable.getCause() : throwable;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    // -- help ----------------------------------------------------------------

    private static final List<HelpLine> HELP_LINES = List.of(
            new HelpLine("ashlar.use", "help.request"),

            new HelpLine("ashlar.use", "help.ask"),
            new HelpLine("ashlar.use", "help.cancel_self"),
            new HelpLine("ashlar.use", "help.reset"),
            new HelpLine("ashlar.use", "help.undo"),
            new HelpLine("ashlar.admin", "help.cancel_other"),
            new HelpLine("ashlar.use", "help.usage_self"),
            new HelpLine("ashlar.monitor", "help.usage_other"),
            new HelpLine("ashlar.admin", "help.limit_set"),
            new HelpLine("ashlar.admin", "help.limit_reset"),
            new HelpLine("ashlar.admin", "help.credit_show"),
            new HelpLine("ashlar.admin", "help.credit_set"),
            new HelpLine("ashlar.admin", "help.pause"),
            new HelpLine("ashlar.admin", "help.resume"),
            new HelpLine("ashlar.admin", "help.allow"),
            new HelpLine("ashlar.admin", "help.deny"),
            new HelpLine("ashlar.admin", "help.allowed"),
            new HelpLine("ashlar.admin", "help.reload")
    );

    /** {@code key} is a {@code lang/*.yml} message key (step8i-prompt.md), not literal text. */
    private record HelpLine(String permission, String key) {
    }

    private void handleHelp(Player player) {
        boolean any = false;
        for (HelpLine line : HELP_LINES) {
            if (player.hasPermission(line.permission())) {
                reply(player, msg(player, line.key()));
                any = true;
            }
        }
        if (!any) {
            reply(player, msg(player, "command.no_permission"));
        }
    }

    // -- shared helpers -------------------------------------------------------

    /**
     * Whether {@code player} may run the subcommand {@code args} parses to.
     * {@code allow}/{@code deny}/{@code allowed}/{@code limit}/{@code
     * pause}/{@code resume}/{@code cancel <player>}/{@code usage
     * <player>|all} need {@code ashlar.admin} or {@code ashlar.monitor};
     * everything else (a plain request, {@code cancel}, {@code usage}) needs
     * {@code ashlar.use} - or, failing that, a place on the plugin-local
     * {@link AllowList} (step6e-prompt.md's {@code agent.everyone-can-use}
     * companion for servers with no permissions plugin). {@code help} needs
     * nothing here; it filters its own output per line.
     */
    private boolean hasAccess(Player player, String[] args, AshlarArgs.Parsed parsed) {
        String required = requiredPermission(args, parsed);
        if (required == null) {
            return true;
        }
        if (required.equals(Access.USE)) {
            return Access.canUse(player, allowList);
        }
        return player.hasPermission(required);
    }

    /**
     * {@code usage} needs {@code ashlar.monitor} only when it names a target ({@code
     * USAGE_OTHER}/{@code USAGE_ALL}); the caller's own usage - with or without a day range,
     * {@code USAGE_SELF} - needs only {@code ashlar.use} (step8g-prompt.md: a range alone must not
     * require monitor). A grammar error ({@code INVALID}) falls back to the old, conservative
     * arg-count heuristic since the real shape (self-range vs. a mistyped target) is not known.
     */
    private static String requiredPermission(String[] args, AshlarArgs.Parsed parsed) {
        String keyword = args[0].toLowerCase(Locale.ROOT);
        return switch (keyword) {
            case "cancel" -> args.length >= 2 ? "ashlar.admin" : "ashlar.use";
            case "usage" -> switch (parsed.kind()) {
                case USAGE_SELF -> "ashlar.use";
                case USAGE_OTHER, USAGE_ALL -> "ashlar.monitor";
                default -> args.length >= 2 ? "ashlar.monitor" : "ashlar.use";
            };
            case "limit", "credit", "pause", "resume", "allow", "deny", "allowed" -> "ashlar.admin";
            case "help" -> null;
            default -> "ashlar.use";
        };
    }

    /**
     * Whether this subcommand talks to a connected Node process at all. {@code credit} is embedded-
     * only and replies for itself in every mode ({@link #handleCredit}), so it is excluded the same
     * way {@code allow}/{@code deny}/{@code allowed}/{@code reset} are.
     */
    private static boolean needsConnection(AshlarArgs.Kind kind) {
        return switch (kind) {
            case HELP, ALLOW, DENY, ALLOWED, RESET, CREDIT_SHOW, CREDIT_SET, UNDO -> false;
            default -> true;
        };
    }

    private void broadcastAdmin(Player by, String action, JsonElement target, List<String> args) {
        JsonObject event = new JsonObject();
        event.addProperty("event", "admin");
        event.addProperty("action", action);
        event.add("by", actorJson(by));
        event.add("target", target);
        JsonArray argsArray = new JsonArray();
        args.forEach(argsArray::add);
        event.add("args", argsArray);
        wsServer.broadcastEvent("chat", event);
    }

    /** Whether {@code agent.mode} is {@code embedded} - the request/admin handlers call {@link #agentService} instead of broadcasting an event. */
    private boolean isEmbedded() {
        return configHolder.get().agent().mode() == PluginConfig.AgentConfig.Mode.EMBEDDED;
    }

    private static AdminActions.By byOf(Player player) {
        return new AdminActions.By(player.getName(), player.getUniqueId().toString());
    }

    /** {@link AdminActions.Target} equivalent of {@link #targetJson}: the real uuid when online, else {@code uuid = null}. */
    private static AdminActions.Target targetOf(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null
                ? new AdminActions.Target(online.getName(), online.getUniqueId().toString())
                : new AdminActions.Target(name, null);
    }

    private static AgentRunner.PlayerInfo playerInfoOf(Player player) {
        Location loc = player.getLocation();
        int[] pos = {loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()};
        String facing = Facing.fromYaw(loc.getYaw());
        int[] offset = Facing.offset(facing);
        int[] inFront = {pos[0] + offset[0], pos[1] + offset[1], pos[2] + offset[2]};
        return new AgentRunner.PlayerInfo(player.getName(), player.getUniqueId().toString(),
                loc.getWorld().getName(), pos, facing, inFront, player.getGameMode().name(),
                PlayerJson.lookingAtText(player));
    }

    /** {@code {"name":..., "uuid":...}} for an online player - the caller of the command. */
    private static JsonObject actorJson(Player player) {
        JsonObject json = new JsonObject();
        json.addProperty("name", player.getName());
        json.addProperty("uuid", player.getUniqueId().toString());
        return json;
    }

    /**
     * {@code {"name":..., "uuid":...}} for a named target: the real uuid when
     * that player is online ({@link Bukkit#getPlayerExact}), otherwise {@code
     * uuid: null} and the name as typed - Node resolves offline players by
     * the name it last saw (step6e-prompt.md).
     */
    private static JsonObject targetJson(String name) {
        Player online = Bukkit.getPlayerExact(name);
        JsonObject json = new JsonObject();
        if (online != null) {
            json.addProperty("name", online.getName());
            json.addProperty("uuid", online.getUniqueId().toString());
        } else {
            json.addProperty("name", name);
            json.add("uuid", JsonNull.INSTANCE);
        }
        return json;
    }

    /**
     * Sends {@code message} (already excluding the "[Ashlar] " prefix) to every
     * online player with {@code ashlar.monitor} other than {@code requester}, unless
     * {@code agent.echo-to-monitors} is off (step6d-prompt.md). Admin actions are
     * never echoed (step6e-prompt.md): Node replies directly to the caller, and
     * tells the affected player about a cancellation.
     */
    private void echoToMonitors(Player requester, Component message) {
        if (!configHolder.get().agent().echoToMonitors()) {
            return;
        }
        Component full = PREFIX.append(message);
        for (Player monitor : Monitors.onlineExcept(requester)) {
            monitor.sendMessage(full);
        }
    }

    /** Same "[Ashlar] " gold prefix as the {@code send_message} RPC. */
    private static void reply(CommandSender sender, String line) {
        sender.sendMessage(PREFIX.append(Component.text(line, NamedTextColor.WHITE)));
    }

    // -- i18n (step8i-prompt.md) ------------------------------------------------

    /**
     * {@code sender}'s effective chat language, per {@code language:} in {@code config.yml}: a
     * console sender (including {@code ashlar simulate}) resolves via {@link
     * Messages#forConsole}, a real player via {@link Messages#forPlayer} and their own {@link
     * Player#locale()} (called here, on the main thread - every {@code onCommand} call is).
     */
    private String effectiveLanguage(CommandSender sender) {
        return sender instanceof Player player
                ? Messages.forPlayer(configHolder.get().language(), player.locale())
                : Messages.forConsole(configHolder.get().language());
    }

    /** Looks up {@code key} in {@code sender}'s effective language, substituting {@code args}. */
    private String msg(CommandSender sender, String key, Object... args) {
        return messages.get(effectiveLanguage(sender), key, args);
    }
}
