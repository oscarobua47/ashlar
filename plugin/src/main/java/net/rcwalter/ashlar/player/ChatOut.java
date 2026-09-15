// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rcwalter.ashlar.agent.ConsolePlayer;
import net.rcwalter.ashlar.agent.Outbox;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.rpc.MainThread;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Delivers a chat message to one online player: the gold {@code "[Ashlar] "} prefix, line
 * splitting on {@code "\n"}, and a compact echo to {@code ashlar.monitor} players when the
 * message is a final reply (extracted from {@link net.rcwalter.ashlar.handler.SendMessageHandler},
 * docs/private/prompts/step8b-prompt.md, so {@link net.rcwalter.ashlar.agent.AgentService} can
 * reuse the exact same delivery rules without going through the {@code send_message} RPC).
 *
 * <p>{@link #deliver} runs on the main thread (the RPC handler and command executor already are
 * one); {@link #deliverAsync} - and this class's {@link Outbox} implementation, which the agent
 * calls from a virtual thread - hops there via {@link MainThread#call}, resolves the player by
 * UUID, and silently drops the message if they are offline (matching {@code send_message}'s
 * at-least-once, no-queue semantics: a disconnected player simply never sees replies for their
 * now-dead session). {@link ConsolePlayer#ID} is special-cased to a plain {@link Logger#info}
 * line instead - it never corresponds to an online player.
 */
public final class ChatOut implements Outbox {

    private final PluginConfig config;
    private final Logger logger;

    public ChatOut(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public record Delivered(int lines, int monitors) {
    }

    /** Splits {@code text} on {@code "\n"}, sends each non-empty line to {@code target}, and echoes final replies to monitors. */
    public Delivered deliver(Player target, String text, boolean finalKind) {
        List<Player> monitors = finalKind && config.agent().echoToMonitors()
                ? Monitors.onlineExcept(target)
                : List.of();
        Component monitorPrefix = Component.text("[Ashlar -> " + target.getName() + "] ", NamedTextColor.GOLD);

        int lines = 0;
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            Component body = Component.text(line, NamedTextColor.WHITE);
            target.sendMessage(Component.text("[Ashlar] ", NamedTextColor.GOLD).append(body));
            for (Player monitor : monitors) {
                monitor.sendMessage(monitorPrefix.append(body));
            }
            lines++;
        }
        return new Delivered(lines, monitors.size());
    }

    /**
     * {@link Outbox#send}: {@link ConsolePlayer#ID} logs at INFO; any other UUID goes through
     * {@link #deliverAsync}.
     */
    @Override
    public void send(UUID uuid, String text, boolean finalKind) {
        if (uuid.equals(ConsolePlayer.ID)) {
            logger.info("[ashlar simulate] " + text);
            return;
        }
        deliverAsync(uuid, text, finalKind);
    }

    /**
     * Hops to the main thread and delivers via {@link #deliver}; does nothing if {@code uuid} is
     * not an online player, or the plugin is no longer enabled (best-effort - a reply arriving
     * during shutdown with nobody left to receive it is not an error).
     */
    public void deliverAsync(UUID uuid, String text, boolean finalKind) {
        MainThread.call(() -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                deliver(player, text, finalKind);
            }
            return null;
        }).exceptionally(e -> null);
    }
}
