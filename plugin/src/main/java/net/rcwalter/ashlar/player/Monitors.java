// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.player;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Players with {@code ashlar.monitor} watching in-game assistant traffic
 * (step6d-prompt.md): every accepted {@code /ashlar} request and every
 * final reply is echoed to them, minus whoever the message is already
 * about, so nobody sees their own traffic twice.
 */
public final class Monitors {

    private Monitors() {
    }

    /** Online players with {@code ashlar.monitor}, excluding {@code exclude}. */
    public static List<Player> onlineExcept(Player exclude) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("ashlar.monitor"))
                .filter(p -> !p.getUniqueId().equals(exclude.getUniqueId()))
                .collect(Collectors.toList());
    }
}
