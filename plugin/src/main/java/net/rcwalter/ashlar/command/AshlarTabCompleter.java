// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tab completion for {@code /ashlar}: subcommands the caller may use, then
 * player names (plus {@code all} for {@code usage} and {@code default} for
 * {@code limit}), then {@code limit}'s kind and {@code off}. Runs on the
 * main thread like the executor. A plain request has no completion after
 * the first word (free text).
 */
public final class AshlarTabCompleter implements TabCompleter {

    private static final List<String> USE_WORDS = List.of("ask", "cancel", "reset", "usage", "help");
    private static final List<String> ADMIN_WORDS = List.of("limit", "credit", "pause", "resume", "allow", "deny", "allowed");
    private static final List<String> LIMIT_KINDS = List.of("cost", "tokens", "requests", "reset");
    private static final List<String> CREDIT_ACTIONS = List.of("add", "set", "off");
    private static final List<String> USAGE_RANGE_WORDS = List.of("7", "30");

    private final AllowList allowList;

    public AshlarTabCompleter(AllowList allowList) {
        this.allowList = allowList;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || args.length == 0) {
            return List.of();
        }
        boolean use = Access.canUse(player, allowList);
        boolean monitor = player.hasPermission(Access.MONITOR);
        boolean admin = player.hasPermission(Access.ADMIN);

        if (args.length == 1) {
            List<String> words = new ArrayList<>();
            if (use) {
                words.addAll(USE_WORDS);
            } else {
                words.add("help");
            }
            if (admin) {
                words.addAll(ADMIN_WORDS);
            }
            return filter(words, args[0]);
        }

        String keyword = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (keyword) {
                case "cancel", "allow", "deny" -> admin ? filter(onlineNames(), args[1]) : List.of();
                case "usage" -> filter(usageSecondWordCandidates(use, monitor), args[1]);
                case "limit" -> admin ? filter(withExtra(onlineNames(), "default"), args[1]) : List.of();
                case "credit" -> admin ? filter(onlineNames(), args[1]) : List.of();
                default -> List.of();
            };
        }
        if (keyword.equals("limit") && admin) {
            if (args.length == 3) {
                return filter(LIMIT_KINDS, args[2]);
            }
            if (args.length == 4 && !args[2].equalsIgnoreCase("reset")) {
                return filter(List.of("off"), args[3]);
            }
        }
        if (keyword.equals("credit") && admin && args.length == 3) {
            return filter(CREDIT_ACTIONS, args[2]);
        }
        // /ashlar usage <player>|all <TAB>: suggest the day-range shortcuts (step8g-prompt.md).
        if (keyword.equals("usage") && monitor && args.length == 3) {
            return filter(USAGE_RANGE_WORDS, args[2]);
        }
        return List.of();
    }

    /**
     * {@code /ashlar usage <TAB>}: player names and {@code all} for a monitor (a named target),
     * plus {@code 7}/{@code 30} for anyone who can use the assistant (their own usage with a day
     * range needs no {@code ashlar.monitor} - step8g-prompt.md).
     */
    private static List<String> usageSecondWordCandidates(boolean use, boolean monitor) {
        List<String> candidates = new ArrayList<>();
        if (monitor) {
            candidates.addAll(withExtra(onlineNames(), "all"));
        }
        if (use) {
            candidates.addAll(USAGE_RANGE_WORDS);
        }
        return candidates;
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    private static List<String> withExtra(List<String> names, String extra) {
        List<String> out = new ArrayList<>(names);
        out.add(0, extra);
        return out;
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(c);
            }
        }
        return out;
    }
}
