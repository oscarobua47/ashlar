// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

import cc.wujm.ashlar.i18n.Messages;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the plugin's {@code admin} chat-subscription actions - {@code usage}, {@code limit},
 * {@code cancel}, {@code pause}, {@code resume} - with the same reply wording as {@code
 * mcp-server/src/agent/admin.ts}'s {@code createAdminHandler}. Kept Bukkit-free: {@link #cancel}
 * and {@link #send} are supplied as small functional interfaces so the caller (the Bukkit-facing
 * wiring, a later step) does not need this class to know about sessions or players.
 *
 * <p>{@link #languageForUuid} (step8i-prompt.md) is the same idea: a {@code uuid -> language}
 * lookup supplied by the caller, so this class can send {@code by} and {@code target} each their
 * own effective language (relevant only when {@code language: auto}) without importing anything
 * Bukkit itself. It is safe for that function to call {@code Bukkit.getPlayer(uuid).locale()}
 * because every public method here ({@link #handle} and the {@code handleX} methods it dispatches
 * to) is documented - and, in {@code AgentService}/{@code AshlarCommand}, only ever called - from
 * the main thread.
 */
public final class AdminActions {

    private static final Logger LOGGER = Logger.getLogger("Ashlar");
    private static final int MAX_USAGE_ALL_LINES = 20;
    private static final List<UsageStore.LimitKind> LIMIT_KINDS =
            List.of(UsageStore.LimitKind.COST, UsageStore.LimitKind.TOKENS, UsageStore.LimitKind.REQUESTS);

    public enum CancelOutcome { RUNNING, QUEUED, NONE }

    public enum SendKind { PROGRESS, FINAL }

    public record By(String name, String uuid) {
    }

    /** {@code uuid} is null when the named player is offline. */
    public record Target(String name, String uuid) {
    }

    @FunctionalInterface
    public interface Cancel {
        CancelOutcome cancel(String uuid);
    }

    @FunctionalInterface
    public interface Send {
        void send(String uuid, String text, SendKind kind);
    }

    private final UsageStore store;
    private final Cancel cancel;
    private final Send send;
    // Not final: agent.pricing.currency is hot (step8j-prompt.md) - AgentService#applyConfig calls
    // setCurrency; every reader here runs on the main thread only (see class javadoc), so a plain
    // field is enough.
    private String currency;
    private final Function<String, String> languageForUuid;
    private final Messages messages = Messages.instance();

    public AdminActions(UsageStore store, Cancel cancel, Send send, String currency) {
        this(store, cancel, send, currency, uuid -> Messages.DEFAULT_LANGUAGE);
    }

    public AdminActions(UsageStore store, Cancel cancel, Send send, String currency, Function<String, String> languageForUuid) {
        this.store = store;
        this.cancel = cancel;
        this.send = send;
        this.currency = currency;
        this.languageForUuid = languageForUuid;
    }

    /** Applies a new {@code agent.pricing.currency} ({@code /ashlar reload}, step8j-prompt.md). */
    public void setCurrency(String currency) {
        this.currency = currency;
    }

    private String lang(String uuid) {
        return languageForUuid.apply(uuid);
    }

    private String msg(String uuid, String key, Object... args) {
        return messages.get(lang(uuid), key, args);
    }

    /** Dispatches one admin action; unknown actions are logged and ignored, matching the TS handler. */
    public void handle(String action, By by, Target target, List<String> args) {
        switch (action) {
            case "usage" -> handleUsage(by, target, args);
            case "limit" -> handleLimit(by, target, args);
            case "credit" -> handleCredit(by, target, args);
            case "cancel" -> handleCancel(by, target);
            case "pause" -> handlePauseResume(by, false);
            case "resume" -> handlePauseResume(by, true);
            default -> LOGGER.log(Level.WARNING, () -> "[agent-admin] unknown action \"" + action + "\"");
        }
    }

    /**
     * {@code args} empty is the original today/total/limits/credit block (unchanged); a range
     * ({@code ["7"]} or two {@code YYYY-MM-DD} dates, already normalised by {@link
     * cc.wujm.ashlar.command.AshlarArgs} - step8g-prompt.md) instead prints one line per UTC
     * day plus a range total.
     */
    public void handleUsage(By by, Target target, List<String> args) {
        if (args.isEmpty()) {
            handleUsageBlock(by, target);
            return;
        }

        RangeResult range = parseRange(by, args);
        if (range.error() != null) {
            send.send(by.uuid(), range.error(), SendKind.FINAL);
            return;
        }

        if (target == null) {
            UsageStore.HistoryResult h = store.history(by.uuid(), range.from(), range.to());
            send.send(by.uuid(), fmtUsageRangeBlock(by.uuid(), by.name(), range.from(), range.to(), h), SendKind.FINAL);
            return;
        }
        if (target.name().equalsIgnoreCase("all")) {
            UsageStore.HistoryResult h = store.historyAll(range.from(), range.to());
            send.send(by.uuid(), fmtUsageRangeBlock(by.uuid(), "all", range.from(), range.to(), h), SendKind.FINAL);
            return;
        }
        Optional<UsageStore.NameMatch> resolved = resolveTarget(target);
        if (resolved.isEmpty()) {
            send.send(by.uuid(), msg(by.uuid(), "admin.unknown_player", target.name()), SendKind.FINAL);
            return;
        }
        UsageStore.HistoryResult h = store.history(resolved.get().uuid(), range.from(), range.to());
        send.send(by.uuid(), fmtUsageRangeBlock(by.uuid(), resolved.get().name(), range.from(), range.to(), h), SendKind.FINAL);
    }

    private void handleUsageBlock(By by, Target target) {
        if (target == null) {
            send.send(by.uuid(), fmtUsageBlock(by.uuid(), store.summary(by.uuid(), by.name())), SendKind.FINAL);
            return;
        }

        if (target.name().equalsIgnoreCase("all")) {
            List<UsageStore.UsageSummary> all = store.summaryAll();
            if (all.isEmpty()) {
                send.send(by.uuid(), msg(by.uuid(), "admin.usage.all.none"), SendKind.FINAL);
                return;
            }
            int shownCount = Math.min(MAX_USAGE_ALL_LINES, all.size());
            List<String> lines = new ArrayList<>();
            lines.add(msg(by.uuid(), "admin.usage.all.header"));
            for (int i = 0; i < shownCount; i++) {
                UsageStore.UsageSummary s = all.get(i);
                String tokens = UsageStore.fmtTokens(s.today().inputTokens + s.today().cachedInputTokens + s.today().outputTokens);
                lines.add(msg(by.uuid(), "admin.usage.all.line", s.name(), s.today().requests, tokens, UsageStore.fmtCost(s.today().cost, currency)));
            }
            if (all.size() > MAX_USAGE_ALL_LINES) {
                lines.add(msg(by.uuid(), "admin.usage.all.more", all.size() - MAX_USAGE_ALL_LINES, MAX_USAGE_ALL_LINES));
            }
            send.send(by.uuid(), String.join("\n", lines), SendKind.FINAL);
            return;
        }

        Optional<UsageStore.NameMatch> resolved = resolveTarget(target);
        if (resolved.isEmpty()) {
            send.send(by.uuid(), msg(by.uuid(), "admin.unknown_player", target.name()), SendKind.FINAL);
            return;
        }
        send.send(by.uuid(), fmtUsageBlock(by.uuid(), store.summary(resolved.get().uuid(), resolved.get().name())), SendKind.FINAL);
    }

    /** {@code error} non-null means {@code from}/{@code to} are unset; otherwise an inclusive, already-clamped UTC date range. */
    private record RangeResult(LocalDate from, LocalDate to, String error) {
        static RangeResult ok(LocalDate from, LocalDate to) {
            return new RangeResult(from, to, null);
        }

        static RangeResult err(String error) {
            return new RangeResult(null, null, error);
        }
    }

    /**
     * {@code args = ["N"]} (1..31, ending today) or {@code args = [from, to]} (YYYY-MM-DD, already
     * normalised upstream; swapped if reversed, at most 31 days apart, each date individually
     * clamped to today when it is in the future).
     */
    private RangeResult parseRange(By by, List<String> args) {
        LocalDate today = LocalDate.parse(store.todayIso());
        if (args.size() == 1) {
            Integer n = parsePositiveInt(args.get(0));
            if (n == null || n < 1 || n > 31) {
                return RangeResult.err(msg(by.uuid(), "admin.usage.range.bad_days", args.get(0)));
            }
            return RangeResult.ok(today.minusDays(n - 1), today);
        }
        if (args.size() == 2) {
            LocalDate a = parseIsoDate(args.get(0));
            LocalDate b = parseIsoDate(args.get(1));
            if (a == null || b == null) {
                return RangeResult.err(msg(by.uuid(), "admin.usage.range.bad_dates"));
            }
            LocalDate from = a.isAfter(b) ? b : a;
            LocalDate to = a.isAfter(b) ? a : b;
            if (ChronoUnit.DAYS.between(from, to) + 1 > 31) {
                return RangeResult.err(msg(by.uuid(), "admin.usage.range.too_long"));
            }
            if (to.isAfter(today)) {
                to = today;
            }
            if (from.isAfter(today)) {
                from = today;
            }
            return RangeResult.ok(from, to);
        }
        return RangeResult.err(msg(by.uuid(), "admin.usage.range.bad_args"));
    }

    private static Integer parsePositiveInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseIsoDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code <date>  <requests> req  <tokens> tok  <cost>} per day, then a {@code total:} line summing the range. */
    private String fmtUsageRangeBlock(String forUuid, String name, LocalDate from, LocalDate to, UsageStore.HistoryResult h) {
        List<String> lines = new ArrayList<>();
        lines.add(msg(forUuid, "admin.usage.range.header", name, from, to));
        for (UsageStore.DayCounters d : h.days()) {
            String tokens = UsageStore.fmtTokens(d.inputTokens + d.cachedInputTokens + d.outputTokens);
            lines.add(msg(forUuid, "admin.usage.range.day", d.date, d.requests, tokens, UsageStore.fmtCost(d.cost, currency)));
        }
        UsageStore.TotalCounters t = h.totals();
        String totalTokens = UsageStore.fmtTokens(t.inputTokens + t.cachedInputTokens + t.outputTokens);
        lines.add(msg(forUuid, "admin.usage.range.total", t.requests, totalTokens, UsageStore.fmtCost(t.cost, currency)));
        return String.join("\n", lines);
    }

    public void handleLimit(By by, Target target, List<String> args) {
        String uuidOrNull;
        String who;
        String nameForCreate;
        if (target == null) {
            uuidOrNull = null;
            who = msg(by.uuid(), "admin.limit.default_target");
            nameForCreate = null;
        } else {
            Optional<UsageStore.NameMatch> resolved = resolveTarget(target);
            if (resolved.isEmpty()) {
                send.send(by.uuid(), msg(by.uuid(), "admin.unknown_player", target.name()), SendKind.FINAL);
                return;
            }
            uuidOrNull = resolved.get().uuid();
            who = resolved.get().name();
            nameForCreate = resolved.get().name();
        }

        if (!args.isEmpty() && "reset".equals(args.get(0))) {
            store.resetLimits(uuidOrNull);
            send.send(by.uuid(), msg(by.uuid(), "admin.limit.reset", who, fmtLimitsLine(by.uuid(), store.effectiveLimits(uuidOrNull), null)), SendKind.FINAL);
            return;
        }

        String kindRaw = !args.isEmpty() ? args.get(0) : null;
        if (!"cost".equals(kindRaw) && !"tokens".equals(kindRaw) && !"requests".equals(kindRaw)) {
            send.send(by.uuid(), msg(by.uuid(), "admin.limit.unknown_kind", kindRaw != null ? kindRaw : ""), SendKind.FINAL);
            return;
        }
        UsageStore.LimitKind kind = UsageStore.LimitKind.fromWire(kindRaw);

        String rawValue = args.size() > 1 ? args.get(1) : null;
        UsageStore.LimitValue value;
        if ("off".equals(rawValue)) {
            value = UsageStore.LimitValue.OFF;
        } else {
            Double n = parseDouble(rawValue);
            if (n == null || !Double.isFinite(n) || n <= 0) {
                send.send(by.uuid(), msg(by.uuid(), "admin.limit.bad_value", rawValue != null ? rawValue : ""), SendKind.FINAL);
                return;
            }
            value = UsageStore.LimitValue.of(n);
        }

        store.setLimit(uuidOrNull, kind, value, nameForCreate);
        send.send(by.uuid(), msg(by.uuid(), "admin.limit.set", who, fmtLimitsLine(by.uuid(), store.effectiveLimits(uuidOrNull), null)), SendKind.FINAL);
    }

    /** {@code credit <player>} shows the balance; {@code credit <player> add|set|off [amount]} manages it. */
    public void handleCredit(By by, Target target, List<String> args) {
        if (target == null) {
            send.send(by.uuid(), msg(by.uuid(), "admin.credit.needs_target"), SendKind.FINAL);
            return;
        }
        Optional<UsageStore.NameMatch> resolved = resolveTarget(target);
        if (resolved.isEmpty()) {
            send.send(by.uuid(), msg(by.uuid(), "admin.unknown_player", target.name()), SendKind.FINAL);
            return;
        }
        String uuid = resolved.get().uuid();
        String name = resolved.get().name();

        if (args.isEmpty()) {
            Optional<UsageStore.Credit> credit = store.creditOf(uuid);
            if (credit.isEmpty()) {
                send.send(by.uuid(), msg(by.uuid(), "admin.credit.none", name), SendKind.FINAL);
            } else {
                send.send(by.uuid(), msg(by.uuid(), "admin.credit.show", name, UsageStore.fmtCredit(credit.get().balance(), currency)), SendKind.FINAL);
            }
            return;
        }

        String action = args.get(0);
        String rawAmount = args.size() > 1 ? args.get(1) : null;
        switch (action) {
            case "off" -> {
                store.disableCredit(uuid);
                send.send(by.uuid(), msg(by.uuid(), "admin.credit.disabled", name), SendKind.FINAL);
            }
            case "add" -> {
                Double n = parseDouble(rawAmount);
                if (n == null || !Double.isFinite(n)) {
                    send.send(by.uuid(), msg(by.uuid(), "admin.credit.bad_number", rawAmount != null ? rawAmount : ""), SendKind.FINAL);
                    return;
                }
                double newBalance = store.addCredit(uuid, name, n);
                send.send(by.uuid(), msg(by.uuid(), "admin.credit.added", name, UsageStore.fmtCredit(newBalance, currency), UsageStore.fmtCost(n, currency)), SendKind.FINAL);
            }
            case "set" -> {
                Double n = parseDouble(rawAmount);
                if (n == null || !Double.isFinite(n) || n <= 0) {
                    send.send(by.uuid(), msg(by.uuid(), "admin.credit.bad_positive", rawAmount != null ? rawAmount : ""), SendKind.FINAL);
                    return;
                }
                store.setCredit(uuid, name, n);
                send.send(by.uuid(), msg(by.uuid(), "admin.credit.set", name, UsageStore.fmtCredit(n, currency)), SendKind.FINAL);
            }
            default -> send.send(by.uuid(), msg(by.uuid(), "admin.credit.unknown_action", action), SendKind.FINAL);
        }
    }

    public void handleCancel(By by, Target target) {
        if (target == null) {
            send.send(by.uuid(), msg(by.uuid(), "admin.cancel.needs_target"), SendKind.FINAL);
            return;
        }
        String uuid = target.uuid() != null ? target.uuid() : store.findByName(target.name()).map(UsageStore.NameMatch::uuid).orElse(null);
        CancelOutcome outcome = uuid != null ? cancel.cancel(uuid) : CancelOutcome.NONE;
        if (outcome == CancelOutcome.NONE) {
            send.send(by.uuid(), msg(by.uuid(), "admin.cancel.none", target.name()), SendKind.FINAL);
            return;
        }
        send.send(by.uuid(), msg(by.uuid(), "admin.cancel.done", target.name()), SendKind.FINAL);
        send.send(uuid, msg(uuid, "admin.cancel.notified", by.name()), SendKind.FINAL);
    }

    public void handlePauseResume(By by, boolean resume) {
        store.setPaused(!resume);
        String key = resume ? "admin.resume.done" : "admin.pause.done";
        send.send(by.uuid(), msg(by.uuid(), key), SendKind.FINAL);
    }

    /**
     * Resolves a non-null target to a {@code {uuid, name}} pair: uses {@code target.uuid()}
     * directly when the plugin supplied one (the player is online), otherwise falls back to the
     * usage store's last-seen name lookup.
     */
    private Optional<UsageStore.NameMatch> resolveTarget(Target target) {
        if (target.uuid() != null) {
            return Optional.of(new UsageStore.NameMatch(target.uuid(), target.name()));
        }
        return store.findByName(target.name());
    }

    private String fmtUsageBlock(String forUuid, UsageStore.UsageSummary s) {
        String todayTokens = UsageStore.fmtTokens(s.today().inputTokens + s.today().cachedInputTokens + s.today().outputTokens);
        String totalTokens = UsageStore.fmtTokens(s.total().inputTokens + s.total().cachedInputTokens + s.total().outputTokens);
        List<String> lines = new ArrayList<>(List.of(
                msg(forUuid, "admin.usage.block.header", s.name()),
                msg(forUuid, "admin.usage.block.today", s.today().requests, todayTokens, UsageStore.fmtCost(s.today().cost, currency)),
                msg(forUuid, "admin.usage.block.total", s.total().requests, totalTokens, UsageStore.fmtCost(s.total().cost, currency)),
                msg(forUuid, "admin.usage.block.limits", fmtLimitsLine(forUuid, s.limits(), s.overrides()))));
        if (s.credit().isPresent()) {
            lines.add(msg(forUuid, "admin.usage.block.credit", UsageStore.fmtCredit(s.credit().get().balance(), currency)));
        }
        return String.join("\n", lines);
    }

    private String fmtLimitValue(String forUuid, UsageStore.LimitKind kind, UsageStore.LimitValue value) {
        if (value.isOff()) {
            return msg(forUuid, "admin.limit.value.unlimited", kind.wire());
        }
        if (kind == UsageStore.LimitKind.COST) {
            return msg(forUuid, "admin.limit.value.cost", UsageStore.fmtCost(value.amount(), currency));
        }
        if (kind == UsageStore.LimitKind.TOKENS) {
            return msg(forUuid, "admin.limit.value.tokens", UsageStore.fmtTokens(value.amount()));
        }
        return msg(forUuid, "admin.limit.value.requests", formatWholeNumber(value.amount()));
    }

    private String fmtLimitsLine(String forUuid, Map<UsageStore.LimitKind, UsageStore.LimitValue> limits, Map<UsageStore.LimitKind, Boolean> overrides) {
        List<String> parts = new ArrayList<>();
        for (UsageStore.LimitKind kind : LIMIT_KINDS) {
            String text = fmtLimitValue(forUuid, kind, limits.get(kind));
            boolean isOverride = overrides != null && Boolean.TRUE.equals(overrides.get(kind));
            parts.add(isOverride ? msg(forUuid, "admin.limit.value.override", text) : text);
        }
        return String.join(", ", parts);
    }

    private static String formatWholeNumber(double d) {
        return d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static Double parseDouble(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
