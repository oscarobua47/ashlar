// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.command;

import java.util.List;
import java.util.Locale;

/**
 * Pure parser for {@code /ashlar}'s argument grammar (step6e-prompt.md), no
 * Bukkit: {@link AshlarCommand} does the permission checks and Bukkit
 * lookups, this class only decides which subcommand was typed and whether
 * its arguments are well-formed.
 *
 * <pre>
 * /ashlar &lt;request text...&gt;
 * /ashlar cancel
 * /ashlar cancel &lt;player&gt;
 * /ashlar usage
 * /ashlar usage &lt;player&gt;|all
 * /ashlar limit &lt;player&gt;|default &lt;cost|tokens|requests&gt; &lt;number&gt;|off
 * /ashlar limit &lt;player&gt;|default reset
 * /ashlar pause
 * /ashlar resume
 * /ashlar allow &lt;player&gt;
 * /ashlar deny &lt;player&gt;
 * /ashlar allowed
 * /ashlar help
 * </pre>
 *
 * The first word decides the subcommand, case-insensitively; {@code usage},
 * {@code limit}, {@code pause}, {@code resume}, {@code allow}, {@code deny},
 * {@code allowed}, {@code help} and {@code cancel} are reserved, so a plain
 * request cannot start with one of them.
 */
public final class AshlarArgs {

    public enum Kind {
        REQUEST, CANCEL_SELF, CANCEL_OTHER, USAGE_SELF, USAGE_OTHER, USAGE_ALL,
        LIMIT, PAUSE, RESUME, ALLOW, DENY, ALLOWED, HELP, INVALID
    }

    /**
     * @param kind       the parsed subcommand, or {@code INVALID} on a grammar violation
     * @param targetName the player name (or {@code "default"}/{@code "all"}) the subcommand
     *                    targets, as typed; {@code null} when the subcommand has no target
     * @param args       extra arguments the caller (Node) needs: {@code [kind, value]} or
     *                    {@code ["reset"]} for {@code LIMIT}, empty otherwise
     * @param error      the grammar line to show the player; non-null only when {@code kind == INVALID}
     */
    public record Parsed(Kind kind, String targetName, List<String> args, String error) {
    }

    public static final String USAGE_TOP = "Usage: /ashlar <what you want> | cancel | usage | help";
    static final String USAGE_CANCEL = "Usage: /ashlar cancel | /ashlar cancel <player>";
    static final String USAGE_USAGE = "Usage: /ashlar usage | /ashlar usage <player>|all";
    static final String USAGE_LIMIT =
            "Usage: /ashlar limit <player>|default <cost|tokens|requests> <number>|off | /ashlar limit <player>|default reset";
    static final String USAGE_PAUSE = "Usage: /ashlar pause";
    static final String USAGE_RESUME = "Usage: /ashlar resume";
    static final String USAGE_ALLOW = "Usage: /ashlar allow <player>";
    static final String USAGE_DENY = "Usage: /ashlar deny <player>";
    static final String USAGE_ALLOWED = "Usage: /ashlar allowed";

    private AshlarArgs() {
    }

    public static Parsed parse(String[] args) {
        if (args.length == 0) {
            return invalid(USAGE_TOP);
        }
        String keyword = args[0].toLowerCase(Locale.ROOT);
        return switch (keyword) {
            case "cancel" -> parseCancel(args);
            case "usage" -> parseUsage(args);
            case "limit" -> parseLimit(args);
            case "pause" -> args.length == 1 ? simple(Kind.PAUSE) : invalid(USAGE_PAUSE);
            case "resume" -> args.length == 1 ? simple(Kind.RESUME) : invalid(USAGE_RESUME);
            case "allow" -> args.length == 2 ? new Parsed(Kind.ALLOW, args[1], List.of(), null) : invalid(USAGE_ALLOW);
            case "deny" -> args.length == 2 ? new Parsed(Kind.DENY, args[1], List.of(), null) : invalid(USAGE_DENY);
            case "allowed" -> args.length == 1 ? simple(Kind.ALLOWED) : invalid(USAGE_ALLOWED);
            case "help" -> simple(Kind.HELP);
            default -> new Parsed(Kind.REQUEST, null, List.of(args), null);
        };
    }

    private static Parsed parseCancel(String[] args) {
        if (args.length == 1) {
            return simple(Kind.CANCEL_SELF);
        }
        if (args.length == 2) {
            return new Parsed(Kind.CANCEL_OTHER, args[1], List.of(), null);
        }
        return invalid(USAGE_CANCEL);
    }

    private static Parsed parseUsage(String[] args) {
        if (args.length == 1) {
            return simple(Kind.USAGE_SELF);
        }
        if (args.length == 2) {
            if (args[1].equalsIgnoreCase("all")) {
                return new Parsed(Kind.USAGE_ALL, "all", List.of(), null);
            }
            return new Parsed(Kind.USAGE_OTHER, args[1], List.of(), null);
        }
        return invalid(USAGE_USAGE);
    }

    private static Parsed parseLimit(String[] args) {
        if (args.length == 3 && args[2].equalsIgnoreCase("reset")) {
            return new Parsed(Kind.LIMIT, args[1], List.of("reset"), null);
        }
        if (args.length == 4) {
            String kind = args[2].toLowerCase(Locale.ROOT);
            if (!kind.equals("cost") && !kind.equals("tokens") && !kind.equals("requests")) {
                return invalid(USAGE_LIMIT);
            }
            String value = args[3];
            if (!isValidLimitValue(value)) {
                return invalid(USAGE_LIMIT);
            }
            String normalizedValue = value.equalsIgnoreCase("off") ? "off" : value;
            return new Parsed(Kind.LIMIT, args[1], List.of(kind, normalizedValue), null);
        }
        return invalid(USAGE_LIMIT);
    }

    private static boolean isValidLimitValue(String value) {
        if (value.equalsIgnoreCase("off")) {
            return true;
        }
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Parsed simple(Kind kind) {
        return new Parsed(kind, null, List.of(), null);
    }

    private static Parsed invalid(String error) {
        return new Parsed(Kind.INVALID, null, List.of(), error);
    }
}
