// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import net.rcwalter.ashlar.agent.model.Usage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Peak/off-peak pricing schedule (pure-Java port of {@code mcp-server/src/agent/pricing.ts}): the
 * configured peak prices apply inside the schedule's windows; {@link #multiplier} applies the
 * off-peak discount outside them. A schedule of "always" disables the discount entirely.
 */
public final class Pricing {

    private Pricing() {
    }

    public enum DayRange { DAILY, MON_FRI, SAT_SUN;

        public String wire() {
            return switch (this) {
                case DAILY -> "daily";
                case MON_FRI -> "mon-fri";
                case SAT_SUN -> "sat-sun";
            };
        }

        public static DayRange fromWire(String s) {
            return switch (s) {
                case "daily" -> DAILY;
                case "mon-fri" -> MON_FRI;
                case "sat-sun" -> SAT_SUN;
                default -> throw new IllegalArgumentException("unknown day range: " + s);
            };
        }
    }

    /** Minutes since UTC midnight, 0-1439. {@code endMin <= startMin} means the window wraps past midnight. */
    public record Window(int startMin, int endMin) {
    }

    /** {@code always} true means every hour is peak; {@code windows} is then empty and ignored. */
    public record Schedule(boolean always, DayRange days, List<Window> windows) {
    }

    /** Peak prices, USD (or {@code currency}) per 1,000,000 tokens. */
    public record Prices(double input, double cachedInput, double output) {
    }

    private static final Pattern WINDOW_RE = Pattern.compile("^(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})$");

    private static Window parseWindow(String token, String raw) {
        Matcher m = WINDOW_RE.matcher(token);
        if (!m.matches()) {
            throw new IllegalArgumentException("window must be HH:MM-HH:MM (got \"" + token + "\" in \"" + raw + "\")");
        }
        int startMin = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
        int endMin = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
        if (startMin > 1439 || endMin > 1439) {
            throw new IllegalArgumentException("window hours/minutes out of range (got \"" + token + "\" in \"" + raw + "\")");
        }
        return new Window(startMin, endMin);
    }

    /**
     * Parses a peak-hours spec: {@code always}, or an optional day range ({@code mon-fri}, {@code
     * sat-sun}, {@code daily}; default {@code daily}) followed by comma-separated {@code
     * HH:MM-HH:MM} UTC windows (a window may wrap midnight, e.g. {@code 22:00-02:00}). Throws
     * {@link IllegalArgumentException} on malformed input.
     */
    public static Schedule parsePeakHours(String raw) {
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("always")) {
            return new Schedule(true, DayRange.DAILY, List.of());
        }

        int firstSpace = trimmed.indexOf(' ');
        DayRange days = DayRange.DAILY;
        String rest = trimmed;
        if (firstSpace > 0) {
            String candidate = trimmed.substring(0, firstSpace).toLowerCase(Locale.ROOT);
            if (candidate.equals("mon-fri") || candidate.equals("sat-sun") || candidate.equals("daily")) {
                days = DayRange.fromWire(candidate);
                rest = trimmed.substring(firstSpace + 1).trim();
            }
        }

        if (rest.isEmpty()) {
            throw new IllegalArgumentException("no time windows given (got \"" + raw + "\")");
        }

        List<Window> windows = new ArrayList<>();
        for (String token : rest.split(",")) {
            windows.add(parseWindow(token.trim(), raw));
        }
        return new Schedule(false, days, List.copyOf(windows));
    }

    /** Whether {@code instant} (evaluated in UTC) falls within {@code schedule}'s day range and time windows. */
    public static boolean isPeak(Schedule schedule, Instant instant) {
        if (schedule.always()) {
            return true;
        }

        ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        DayOfWeek dow = utc.getDayOfWeek();
        boolean isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        if (schedule.days() == DayRange.MON_FRI && isWeekend) {
            return false;
        }
        if (schedule.days() == DayRange.SAT_SUN && !isWeekend) {
            return false;
        }

        int minuteOfDay = utc.getHour() * 60 + utc.getMinute();
        for (Window w : schedule.windows()) {
            if (w.startMin() <= w.endMin()) {
                if (minuteOfDay >= w.startMin() && minuteOfDay < w.endMin()) {
                    return true;
                }
            } else {
                // Wraps midnight, e.g. 22:00-02:00.
                if (minuteOfDay >= w.startMin() || minuteOfDay < w.endMin()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The price multiplier in effect at {@code instant}: 1 during peak hours, {@code offPeakMultiplier} otherwise. */
    public static double multiplier(Schedule schedule, double offPeakMultiplier, Instant instant) {
        return isPeak(schedule, instant) ? 1 : offPeakMultiplier;
    }

    /** The cost of one call's {@link Usage} at {@code prices}, scaled by {@code multiplier}. */
    public static double cost(Usage usage, Prices prices, double multiplier) {
        return (usage.inputTokens() / 1_000_000.0) * prices.input() * multiplier
                + (usage.cachedInputTokens() / 1_000_000.0) * prices.cachedInput() * multiplier
                + (usage.outputTokens() / 1_000_000.0) * prices.output() * multiplier;
    }
}
