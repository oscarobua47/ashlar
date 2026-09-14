// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.command;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.net.WsServer;
import net.rcwalter.ashlar.player.Monitors;
import net.rcwalter.ashlar.player.PlayerJson;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code /ashlar <request>} / {@code /ashlar cancel}: the in-game entry
 * point into the AI assistant (step6a-prompt.md). Bukkit runs command
 * executors on the main thread, so this may read Bukkit state freely; the
 * only network action it takes is {@link WsServer#broadcastEvent}, which
 * just enqueues bytes on the socket. This step only pushes the request as a
 * {@code chat} (or {@code chat_cancel}) event to a subscribed connection -
 * nothing here talks to an LLM; that is a connected {@code ashlar-mcp
 * --agent} process, built in the next step.
 */
public final class AshlarCommand implements CommandExecutor {

    private static final Component PREFIX = Component.text("[Ashlar] ", NamedTextColor.GOLD);
    private static final String USAGE = "Usage: /ashlar <what you want> | /ashlar cancel";

    private final PluginConfig config;
    private final WsServer wsServer;
    private final Cooldown cooldown;
    private final AtomicLong requestCounter = new AtomicLong();

    public AshlarCommand(PluginConfig config, WsServer wsServer, Cooldown cooldown) {
        this.config = config;
        this.wsServer = wsServer;
        this.cooldown = cooldown;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            reply(sender, "This command can only be used by a player.");
            return true;
        }
        if (args.length == 0) {
            reply(sender, USAGE);
            return true;
        }
        if (!config.agent().enabled()) {
            reply(sender, "The AI assistant is disabled on this server.");
            return true;
        }
        if (!wsServer.hasSubscriber("chat")) {
            reply(sender, "The AI assistant is not connected right now.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            JsonObject playerRef = new JsonObject();
            playerRef.addProperty("name", player.getName());
            playerRef.addProperty("uuid", player.getUniqueId().toString());
            JsonObject event = new JsonObject();
            event.addProperty("event", "chat_cancel");
            event.add("player", playerRef);
            wsServer.broadcastEvent("chat", event);
            echoToMonitors(player, Component.text(player.getName() + " cancelled their request", NamedTextColor.GRAY));
            reply(player, "Cancel requested.");
            return true;
        }

        String text = String.join(" ", args);
        int maxLength = config.agent().maxMessageLength();
        if (text.length() > maxLength) {
            reply(player, "Request too long (max " + maxLength + " characters).");
            return true;
        }

        long remainingMillis = cooldown.remainingMillis(player.getUniqueId());
        if (remainingMillis > 0) {
            long remainingSeconds = (remainingMillis + 999) / 1000;
            reply(player, "Please wait " + remainingSeconds + " s before the next request.");
            return true;
        }

        cooldown.record(player.getUniqueId());
        JsonObject event = new JsonObject();
        event.addProperty("event", "chat");
        event.addProperty("requestId", "chat-" + requestCounter.incrementAndGet());
        event.add("player", PlayerJson.describe(player));
        event.addProperty("text", text);
        wsServer.broadcastEvent("chat", event);
        echoToMonitors(player, Component.text(player.getName() + " asked: ", NamedTextColor.GRAY)
                .append(Component.text(text, NamedTextColor.WHITE)));
        reply(player, "Sent to the AI assistant. Replies will appear here.");
        return true;
    }

    /**
     * Sends {@code message} (already excluding the "[Ashlar] " prefix) to every
     * online player with {@code ashlar.monitor} other than {@code requester}, unless
     * {@code agent.echo-to-monitors} is off (step6d-prompt.md).
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
