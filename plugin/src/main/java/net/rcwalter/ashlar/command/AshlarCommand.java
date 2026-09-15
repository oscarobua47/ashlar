// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rcwalter.ashlar.agent.AdminActions;
import net.rcwalter.ashlar.agent.AgentRunner;
import net.rcwalter.ashlar.agent.AgentService;
import net.rcwalter.ashlar.agent.ConsolePlayer;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.net.WsServer;
import net.rcwalter.ashlar.player.Facing;
import net.rcwalter.ashlar.player.Monitors;
import net.rcwalter.ashlar.player.PlayerJson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code /ashlar}: the in-game entry point into the AI assistant
 * (step6a-prompt.md) and, from step6e-prompt.md on, its admin surface
 * (usage/limit/credit/cancel &lt;player&gt;/pause/resume/allow/deny/allowed/help).
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

    private static final String NOT_CONFIGURED =
            "The AI assistant is not configured (agent.model.api-key is empty).";

    private final PluginConfig config;
    private final WsServer wsServer;
    private final Cooldown cooldown;
    private final AllowList allowList;
    private final AgentService agentService;
    private final AtomicLong requestCounter = new AtomicLong();

    public AshlarCommand(PluginConfig config, WsServer wsServer, Cooldown cooldown, AllowList allowList,
                          AgentService agentService) {
        this.config = config;
        this.wsServer = wsServer;
        this.cooldown = cooldown;
        this.allowList = allowList;
        this.agentService = agentService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean consoleSender = !(sender instanceof Player);
        if (args.length == 0) {
            reply(sender, AshlarArgs.USAGE_TOP);
            return true;
        }

        AshlarArgs.Parsed parsed = AshlarArgs.parse(args, consoleSender);

        if (parsed.kind() == AshlarArgs.Kind.SIMULATE) {
            handleSimulate(sender, parsed.simulate());
            return true;
        }
        if (consoleSender) {
            if (parsed.kind() == AshlarArgs.Kind.INVALID && args[0].equalsIgnoreCase("simulate")) {
                reply(sender, parsed.error());
            } else {
                reply(sender, "This command can only be used by a player.");
            }
            return true;
        }

        Player player = (Player) sender;
        if (!hasAccess(player, args, parsed)) {
            reply(player, "You do not have permission to do that.");
            return true;
        }
        if (parsed.kind() == AshlarArgs.Kind.INVALID) {
            reply(player, parsed.error());
            return true;
        }

        PluginConfig.AgentConfig.Mode mode = config.agent().mode();
        if (mode == PluginConfig.AgentConfig.Mode.OFF && parsed.kind() != AshlarArgs.Kind.HELP) {
            reply(player, "The AI assistant is disabled on this server.");
            return true;
        }
        if (mode == PluginConfig.AgentConfig.Mode.EXTERNAL && needsConnection(parsed.kind())
                && !wsServer.hasSubscriber("chat")) {
            reply(player, "The AI assistant is not connected right now.");
            return true;
        }
        if (mode == PluginConfig.AgentConfig.Mode.EMBEDDED && needsConnection(parsed.kind()) && agentService == null) {
            reply(player, NOT_CONFIGURED);
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
            case HELP -> handleHelp(player);
            case SIMULATE -> throw new IllegalStateException("handled above");
            case INVALID -> throw new IllegalStateException("handled above");
        }
        return true;
    }

    // -- simulate (console only) ---------------------------------------

    private void handleSimulate(CommandSender sender, AshlarArgs.Simulate sim) {
        PluginConfig.AgentConfig.Mode mode = config.agent().mode();
        if (mode == PluginConfig.AgentConfig.Mode.OFF) {
            reply(sender, "The AI assistant is disabled on this server.");
            return;
        }
        if (mode != PluginConfig.AgentConfig.Mode.EMBEDDED) {
            reply(sender, "ashlar simulate requires agent.mode: embedded.");
            return;
        }
        if (agentService == null) {
            reply(sender, NOT_CONFIGURED);
            return;
        }
        int[] pos = {sim.x(), sim.y(), sim.z()};
        int[] offset = Facing.offset(sim.facing());
        int[] inFront = {pos[0] + offset[0], pos[1] + offset[1], pos[2] + offset[2]};
        AgentRunner.PlayerInfo playerInfo = new AgentRunner.PlayerInfo(
                ConsolePlayer.NAME, ConsolePlayer.ID.toString(), config.world().defaultWorld(),
                pos, sim.facing(), inFront, "CREATIVE", null);
        agentService.submit(playerInfo, String.join(" ", sim.text()));
    }

    // -- request -------------------------------------------------------

    private void handleRequest(Player player, List<String> words) {
        String text = String.join(" ", words);
        int maxLength = config.agent().maxMessageLength();
        if (text.length() > maxLength) {
            reply(player, "Request too long (max " + maxLength + " characters).");
            return;
        }

        long remainingMillis = cooldown.remainingMillis(player.getUniqueId());
        if (remainingMillis > 0) {
            long remainingSeconds = (remainingMillis + 999) / 1000;
            reply(player, "Please wait " + remainingSeconds + " s before the next request.");
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
        reply(player, "Sent to the AI assistant. Replies will appear here.");
    }

    // -- cancel ----------------------------------------------------------

    private void handleCancelSelf(Player player) {
        echoToMonitors(player, Component.text(player.getName() + " cancelled their request", NamedTextColor.GRAY));

        if (isEmbedded()) {
            boolean cancelled = agentService.cancel(player.getUniqueId());
            reply(player, cancelled ? "Cancel requested." : "Nothing to cancel.");
            return;
        }

        JsonObject event = new JsonObject();
        event.addProperty("event", "chat_cancel");
        event.add("player", actorJson(player));
        wsServer.broadcastEvent("chat", event);
        reply(player, "Cancel requested.");
    }

    private void handleCancelOther(Player player, String targetName) {
        if (isEmbedded()) {
            agentService.admin("cancel", byOf(player), targetOf(targetName), List.of());
            return;
        }
        broadcastAdmin(player, "cancel", targetJson(targetName), List.of());
        reply(player, "Cancel requested for " + targetName + ".");
    }

    // -- usage -------------------------------------------------------------

    private void handleReset(Player player) {
        if (!isEmbedded()) {
            reply(player, "/ashlar reset is only available in embedded mode.");
            return;
        }
        boolean had = agentService.reset(player.getUniqueId());
        reply(player, had ? "Forgot our previous conversation." : "Nothing to forget.");
    }

    private void handleUsageSelf(Player player, List<String> rangeArgs) {
        if (isEmbedded()) {
            agentService.admin("usage", byOf(player), null, rangeArgs);
            return;
        }
        broadcastAdmin(player, "usage", JsonNull.INSTANCE, rangeArgs);
        reply(player, "Usage request sent.");
    }

    private void handleUsageOther(Player player, String targetName, List<String> rangeArgs) {
        if (isEmbedded()) {
            agentService.admin("usage", byOf(player), targetOf(targetName), rangeArgs);
            return;
        }
        broadcastAdmin(player, "usage", targetJson(targetName), rangeArgs);
        reply(player, "Usage request sent.");
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
        reply(player, "Usage request sent.");
    }

    // -- limit / pause / resume ---------------------------------------------

    private void handleLimit(Player player, String targetName, List<String> limitArgs) {
        boolean isDefault = targetName.equalsIgnoreCase("default");
        if (isEmbedded()) {
            agentService.admin("limit", byOf(player), isDefault ? null : targetOf(targetName), limitArgs);
            return;
        }
        broadcastAdmin(player, "limit", isDefault ? JsonNull.INSTANCE : targetJson(targetName), limitArgs);
        reply(player, "Limit change sent.");
    }

    /**
     * {@code /ashlar credit <player>} (show, {@code creditArgs} empty) and {@code /ashlar credit
     * <player> add|set|off [amount]} - embedded mode only; external mode has no local accounting to
     * show or change, so it just says so (step8f-prompt.md).
     */
    private void handleCredit(Player player, String targetName, List<String> creditArgs) {
        if (!isEmbedded()) {
            reply(player, "Credit is managed by the external agent.");
            return;
        }
        if (agentService == null) {
            reply(player, NOT_CONFIGURED);
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
        reply(player, "Pause requested.");
    }

    private void handleResume(Player player) {
        if (isEmbedded()) {
            agentService.admin("resume", byOf(player), null, List.of());
            return;
        }
        broadcastAdmin(player, "resume", JsonNull.INSTANCE, List.of());
        reply(player, "Resume requested.");
    }

    // -- allow / deny / allowed: plugin-local, no event to Node -------------

    private void handleAllow(Player player, String targetName) {
        if (allowList.add(targetName)) {
            reply(player, "Added " + targetName + " to the allow list.");
        } else {
            reply(player, targetName + " is already on the allow list.");
        }
    }

    private void handleDeny(Player player, String targetName) {
        if (allowList.remove(targetName)) {
            reply(player, "Removed " + targetName + ".");
        } else {
            reply(player, targetName + " was not on the allow list.");
        }
    }

    private void handleAllowed(Player player) {
        List<String> names = allowList.names();
        if (names.isEmpty()) {
            reply(player, "The allow list is empty.");
        } else {
            reply(player, "Allow list: " + String.join(", ", names));
        }
    }

    // -- help ----------------------------------------------------------------

    private static final List<HelpLine> HELP_LINES = List.of(
            new HelpLine("ashlar.use", "/ashlar <what you want> - ask the assistant to build or change something"),

            new HelpLine("ashlar.use", "/ashlar ask <what you want> - same, for requests that start with a command word"),
            new HelpLine("ashlar.use", "/ashlar cancel - cancel your own running or queued request"),
            new HelpLine("ashlar.use", "/ashlar reset - forget the previous conversation (start fresh)"),
            new HelpLine("ashlar.admin", "/ashlar cancel <player> - cancel another player's request"),
            new HelpLine("ashlar.use", "/ashlar usage [<days>|<from> <to>] - your own usage today/total, or a per-day report (dates: YYYY-MM-DD, YYYYMMDD, or MM-DD)"),
            new HelpLine("ashlar.monitor", "/ashlar usage <player>|all [<days>|<from> <to>] - another player's usage, or everyone's, optionally as a per-day report"),
            new HelpLine("ashlar.admin", "/ashlar limit <player>|default <cost|tokens|requests> <number>|off - set a daily cap"),
            new HelpLine("ashlar.admin", "/ashlar limit <player>|default reset - remove the override"),
            new HelpLine("ashlar.admin", "/ashlar credit <player> - show a player's prepaid credit balance"),
            new HelpLine("ashlar.admin", "/ashlar credit <player> <add|set> <number>|off - manage a player's prepaid credit"),
            new HelpLine("ashlar.admin", "/ashlar pause - stop the assistant from accepting requests"),
            new HelpLine("ashlar.admin", "/ashlar resume - let the assistant accept requests again"),
            new HelpLine("ashlar.admin", "/ashlar allow <player> - let a player use /ashlar without a permission"),
            new HelpLine("ashlar.admin", "/ashlar deny <player> - remove a player from the allow list"),
            new HelpLine("ashlar.admin", "/ashlar allowed - list players on the allow list")
    );

    private record HelpLine(String permission, String text) {
    }

    private void handleHelp(Player player) {
        boolean any = false;
        for (HelpLine line : HELP_LINES) {
            if (player.hasPermission(line.permission())) {
                reply(player, line.text());
                any = true;
            }
        }
        if (!any) {
            reply(player, "You do not have permission to do that.");
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
            case HELP, ALLOW, DENY, ALLOWED, RESET, CREDIT_SHOW, CREDIT_SET -> false;
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
        return config.agent().mode() == PluginConfig.AgentConfig.Mode.EMBEDDED;
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
        if (!config.agent().echoToMonitors()) {
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
}
