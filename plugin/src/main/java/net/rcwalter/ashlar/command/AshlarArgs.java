// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.command;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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
 * /ashlar usage [&lt;player&gt;|all] &lt;days&gt;
 * /ashlar usage [&lt;player&gt;|all] &lt;from&gt; &lt;to&gt; (each YYYY-MM-DD, YYYYMMDD, or MM-DD for the current UTC year)
 * /ashlar limit &lt;player&gt;|default &lt;cost|tokens|requests&gt; &lt;number&gt;|off
 * /ashlar limit &lt;player&gt;|default reset
 * /ashlar credit &lt;player&gt;
 * /ashlar credit &lt;player&gt; &lt;add|set&gt; &lt;number&gt;
 * /ashlar credit &lt;player&gt; off
 * /ashlar pause
 * /ashlar resume
 * /ashlar allow &lt;player&gt;
 * /ashlar deny &lt;player&gt;
 * /ashlar allowed
 * /ashlar help
 * </pre>
 *
 * The first word decides the subcommand, case-insensitively; {@code usage},
 * {@code limit}, {@code credit}, {@code pause}, {@code resume}, {@code allow},
 * {@code deny}, {@code allowed}, {@code help} and {@code cancel} are reserved,
 * so a plain request cannot start with one of them.
 */
public final class AshlarArgs {

    public enum Kind {
        REQUEST, CANCEL_SELF, CANCEL_OTHER, RESET, USAGE_SELF, USAGE_OTHER, USAGE_ALL,
        LIMIT, CREDIT_SHOW, CREDIT_SET, PAUSE, RESUME, ALLOW, DENY, ALLOWED, HELP, SIMULATE, INVALID
    }

    /** {@code ashlar simulate <x> <y> <z> [facing] <text...>} (step8b-prompt.md), console only. */
    public record Simulate(int x, int y, int z, String facing, List<String> text) {
    }

    /**
     * @param kind       the parsed subcommand, or {@code INVALID} on a grammar violation
     * @param targetName the player name (or {@code "default"}/{@code "all"}) the subcommand
     *                    targets, as typed; {@code null} when the subcommand has no target
     * @param args       extra arguments the caller (Node) needs: {@code [kind, value]} or
     *                    {@code ["reset"]} for {@code LIMIT}, empty otherwise
     * @param error      the grammar line to show the player; non-null only when {@code kind == INVALID}
     * @param simulate   non-null only when {@code kind == SIMULATE}
     */
    public record Parsed(Kind kind, String targetName, List<String> args, String error, Simulate simulate) {
        public Parsed(Kind kind, String targetName, List<String> args, String error) {
            this(kind, targetName, args, error, null);
        }
    }

    public static final String USAGE_TOP = "Usage: /ashlar <what you want> | ask <what you want> | cancel | usage | help";
    static final String USAGE_ASK = "Usage: /ashlar ask <what you want>";
    static final String USAGE_RESET = "Usage: /ashlar reset";
    static final String USAGE_CANCEL = "Usage: /ashlar cancel | /ashlar cancel <player>";
    static final String USAGE_USAGE =
            "Usage: /ashlar usage [<player>|all] [<days 1-31> | <from> <to>] (dates: YYYY-MM-DD, YYYYMMDD, or MM-DD for this year)";
    static final String USAGE_LIMIT =
            "Usage: /ashlar limit <player>|default <cost|tokens|requests> <number>|off | /ashlar limit <player>|default reset";
    static final String USAGE_CREDIT =
            "Usage: /ashlar credit <player> | /ashlar credit <player> <add|set> <number> | /ashlar credit <player> off";
    static final String USAGE_PAUSE = "Usage: /ashlar pause";
    static final String USAGE_RESUME = "Usage: /ashlar resume";
    static final String USAGE_ALLOW = "Usage: /ashlar allow <player>";
    static final String USAGE_DENY = "Usage: /ashlar deny <player>";
    static final String USAGE_ALLOWED = "Usage: /ashlar allowed";
    static final String USAGE_SIMULATE = "Usage: ashlar simulate <x> <y> <z> [facing] <text...> (console only)";

    private static final List<String> FACINGS = List.of("south", "west", "north", "east");

    private AshlarArgs() {
    }

    /** Equivalent to {@link #parse(String[], boolean)} with {@code consoleSender = false}: a player's grammar. */
    public static Parsed parse(String[] args) {
        return parse(args, false);
    }

    /**
     * @param consoleSender whether the caller is not a {@code Player} (console, command block,
     *                      etc.) - only {@code simulate} (step8b-prompt.md) reads this; every
     *                      other keyword parses the same regardless.
     */
    public static Parsed parse(String[] args, boolean consoleSender) {
        if (args.length == 0) {
            return invalid(USAGE_TOP);
        }
        String keyword = args[0].toLowerCase(Locale.ROOT);
        return switch (keyword) {
            case "ask" -> args.length >= 2
                    ? new Parsed(Kind.REQUEST, null, List.of(args).subList(1, args.length), null)
                    : invalid(USAGE_ASK);
            case "cancel" -> parseCancel(args);
            case "usage" -> parseUsage(args);
            case "limit" -> parseLimit(args);
            case "credit" -> parseCredit(args);
            case "pause" -> args.length == 1 ? simple(Kind.PAUSE) : invalid(USAGE_PAUSE);
            case "resume" -> args.length == 1 ? simple(Kind.RESUME) : invalid(USAGE_RESUME);
            case "allow" -> args.length == 2 ? new Parsed(Kind.ALLOW, args[1], List.of(), null) : invalid(USAGE_ALLOW);
            case "deny" -> args.length == 2 ? new Parsed(Kind.DENY, args[1], List.of(), null) : invalid(USAGE_DENY);
            case "allowed" -> args.length == 1 ? simple(Kind.ALLOWED) : invalid(USAGE_ALLOWED);
            case "reset" -> args.length == 1 ? simple(Kind.RESET) : invalid(USAGE_RESET);
            case "help" -> simple(Kind.HELP);
            case "simulate" -> parseSimulate(args, consoleSender);
            default -> new Parsed(Kind.REQUEST, null, List.of(args), null);
        };
    }

    private static Parsed parseSimulate(String[] args, boolean consoleSender) {
        if (!consoleSender || args.length < 5) {
            return invalid(USAGE_SIMULATE);
        }
        Integer x = parseInt(args[1]);
        Integer y = parseInt(args[2]);
        Integer z = parseInt(args[3]);
        if (x == null || y == null || z == null) {
            return invalid(USAGE_SIMULATE);
        }
        String facingCandidate = args[4].toLowerCase(Locale.ROOT);
        String facing;
        int textStart;
        if (FACINGS.contains(facingCandidate)) {
            facing = facingCandidate;
            textStart = 5;
        } else {
            facing = "south";
            textStart = 4;
        }
        if (textStart >= args.length) {
            return invalid(USAGE_SIMULATE);
        }
        List<String> text = List.copyOf(List.of(args).subList(textStart, args.length));
        return new Parsed(Kind.SIMULATE, null, List.of(), null, new Simulate(x, y, z, facing, text));
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
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

    private static final Pattern DIGITS_ONLY = Pattern.compile("[0-9]+");
    private static final Pattern ISO_DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern COMPACT_DATE_SHAPE = Pattern.compile("\\d{8}");
    private static final Pattern MONTH_DAY_SHAPE = Pattern.compile("\\d{1,2}-\\d{1,2}");

    /**
     * {@code usage [player|all] [days | from to]} (step8g-prompt.md): after the optional target,
     * a bare token is either the whole remaining range (no target - the caller's own usage) or
     * the target's range. A token that merely looks like a range (all digits, {@code YYYY-MM-DD},
     * {@code YYYYMMDD}, or {@code MM-DD}/{@code M-D} shaped) is never re-read as a player name if
     * it fails as a range - that is the "bad N" / "one date with no pair" invalid case, not a
     * player named "40" or "2026-01-01".
     */
    private static Parsed parseUsage(String[] args) {
        if (args.length == 1) {
            return simple(Kind.USAGE_SELF);
        }
        List<String> rest = List.of(args).subList(1, args.length);

        if (rest.size() == 1) {
            String token = rest.get(0);
            if (token.equalsIgnoreCase("all")) {
                return new Parsed(Kind.USAGE_ALL, "all", List.of(), null);
            }
            if (looksLikeRangeToken(token)) {
                return parseValidDays(token) != null ? new Parsed(Kind.USAGE_SELF, null, List.of(token), null) : invalid(USAGE_USAGE);
            }
            return new Parsed(Kind.USAGE_OTHER, token, List.of(), null);
        }

        if (rest.size() == 2) {
            String fromA = normalizeDate(rest.get(0));
            String toA = normalizeDate(rest.get(1));
            if (fromA != null && toA != null) {
                return new Parsed(Kind.USAGE_SELF, null, List.of(fromA, toA), null);
            }
        }

        if (rest.size() == 2 || rest.size() == 3) {
            String target = rest.get(0);
            List<String> rangeTokens = rest.subList(1, rest.size());
            boolean isAll = target.equalsIgnoreCase("all");
            Kind kind = isAll ? Kind.USAGE_ALL : Kind.USAGE_OTHER;
            String targetName = isAll ? "all" : target;

            if (rangeTokens.size() == 1) {
                return parseValidDays(rangeTokens.get(0)) != null
                        ? new Parsed(kind, targetName, List.copyOf(rangeTokens), null)
                        : invalid(USAGE_USAGE);
            }
            String from = normalizeDate(rangeTokens.get(0));
            String to = normalizeDate(rangeTokens.get(1));
            if (from != null && to != null) {
                return new Parsed(kind, targetName, List.of(from, to), null);
            }
            return invalid(USAGE_USAGE);
        }

        return invalid(USAGE_USAGE);
    }

    private static boolean looksLikeRangeToken(String s) {
        return DIGITS_ONLY.matcher(s).matches() || ISO_DATE_SHAPE.matcher(s).matches() || MONTH_DAY_SHAPE.matcher(s).matches();
    }

    /** {@code null} unless {@code s} is all-digits and parses to an integer in {@code 1..31}. */
    private static Integer parseValidDays(String s) {
        if (!DIGITS_ONLY.matcher(s).matches()) {
            return null;
        }
        try {
            int n = Integer.parseInt(s);
            return n >= 1 && n <= 31 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Normalises one range date to {@code YYYY-MM-DD}, accepting three spellings (step8g-prompt.md
     * addendum): {@code YYYY-MM-DD} as-is; {@code YYYYMMDD} (8 digits, no separators); and {@code
     * MM-DD} or {@code M-D} (month and day only, taken in the current UTC year). Returns {@code
     * null} for anything else, including a real-looking date that is not a real calendar date
     * (e.g. day 31 of a 30-day month) and month-day shapes such as {@code 2026-09-01} which is
     * read as a full ISO date, not a month-day pair.
     */
    private static String normalizeDate(String s) {
        if (ISO_DATE_SHAPE.matcher(s).matches()) {
            try {
                return LocalDate.parse(s).toString();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        if (COMPACT_DATE_SHAPE.matcher(s).matches()) {
            try {
                return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE).toString();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        if (MONTH_DAY_SHAPE.matcher(s).matches()) {
            String[] parts = s.split("-");
            try {
                int month = Integer.parseInt(parts[0]);
                int day = Integer.parseInt(parts[1]);
                int year = LocalDate.now(ZoneOffset.UTC).getYear();
                return LocalDate.of(year, month, day).toString();
            } catch (NumberFormatException | DateTimeException e) {
                return null;
            }
        }
        return null;
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

    /**
     * {@code credit <player>} (show, no args); {@code credit <player> off} ({@code args = ["off"]});
     * {@code credit <player> add|set <number>} ({@code args = [action, value]}, value a positive number).
     */
    private static Parsed parseCredit(String[] args) {
        if (args.length == 2) {
            return new Parsed(Kind.CREDIT_SHOW, args[1], List.of(), null);
        }
        if (args.length < 3) {
            return invalid(USAGE_CREDIT);
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        if (action.equals("off")) {
            return args.length == 3 ? new Parsed(Kind.CREDIT_SET, args[1], List.of("off"), null) : invalid(USAGE_CREDIT);
        }
        if (!action.equals("add") && !action.equals("set")) {
            return invalid(USAGE_CREDIT);
        }
        if (args.length != 4) {
            return invalid(USAGE_CREDIT);
        }
        String value = args[3];
        if (!isValidPositiveNumber(value)) {
            return invalid(USAGE_CREDIT);
        }
        return new Parsed(Kind.CREDIT_SET, args[1], List.of(action, value), null);
    }

    private static boolean isValidPositiveNumber(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed > 0;
        } catch (NumberFormatException e) {
            return false;
        }
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
