// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.command;

import org.bukkit.entity.Player;

/**
 * The one place that decides whether a player may use the assistant at the
 * {@code ashlar.use} level, shared by the command executor and the tab
 * completer. An explicit grant or denial from a permissions plugin (or an
 * op / {@code agent.everyone-can-use} default that applies) always wins; the
 * plugin-local {@link AllowList} is only consulted when nobody has said
 * anything about this player, so {@code permission set ashlar.use false}
 * cannot be undone by a stale allow-list entry.
 */
final class Access {

    static final String USE = "ashlar.use";
    static final String MONITOR = "ashlar.monitor";
    static final String ADMIN = "ashlar.admin";

    private Access() {
    }

    static boolean canUse(Player player, AllowList allowList) {
        if (player.isPermissionSet(USE)) {
            return player.hasPermission(USE);
        }
        return allowList.contains(player.getName());
    }
}
