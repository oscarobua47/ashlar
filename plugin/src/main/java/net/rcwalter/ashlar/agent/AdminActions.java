// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the plugin's {@code admin} chat-subscription actions - {@code usage}, {@code limit},
 * {@code cancel}, {@code pause}, {@code resume} - with the same reply wording as {@code
 * mcp-server/src/agent/admin.ts}'s {@code createAdminHandler}. Kept Bukkit-free: {@link #cancel}
 * and {@link #send} are supplied as small functional interfaces so the caller (the Bukkit-facing
 * wiring, a later step) does not need this class to know about sessions or players.
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
    private final String currency;

    public AdminActions(UsageStore store, Cancel cancel, Send send, String currency) {
        this.store = store;
        this.cancel = cancel;
        this.send = send;
        this.currency = currency;
    }

    /** Dispatches one admin action; unknown actions are logged and ignored, matching the TS handler. */
    public void handle(String action, By by, Target target, List<String> args) {
        switch (action) {
            case "usage" -> handleUsage(by, target);
            case "limit" -> handleLimit(by, target, args);
            case "cancel" -> handleCancel(by, target);
            case "pause" -> handlePauseResume(by, false);
            case "resume" -> handlePauseResume(by, true);
            default -> LOGGER.log(Level.WARNING, () -> "[agent-admin] unknown action \"" + action + "\"");
        }
    }

    public void handleUsage(By by, Target target) {
        if (target == null) {
            send.send(by.uuid(), fmtUsageBlock(store.summary(by.uuid(), by.name())), SendKind.FINAL);
            return;
        }

        if (target.name().equalsIgnoreCase("all")) {
            List<UsageStore.UsageSummary> all = store.summaryAll();
            if (all.isEmpty()) {
                send.send(by.uuid(), "No usage recorded yet.", SendKind.FINAL);
                return;
            }
            int shownCount = Math.min(MAX_USAGE_ALL_LINES, all.size());
            List<String> lines = new ArrayList<>();
            lines.add("Usage today, by player (sorted by cost):");
            for (int i = 0; i < shownCount; i++) {
                UsageStore.UsageSummary s = all.get(i);
                String tokens = UsageStore.fmtTokens(s.today().inputTokens + s.today().cachedInputTokens + s.today().outputTokens);
                lines.add(s.name() + ": " + s.today().requests + " requests, " + tokens + " tokens, " + UsageStore.fmtCost(s.today().cost, currency));
            }
            if (all.size() > MAX_USAGE_ALL_LINES) {
                lines.add("... and " + (all.size() - MAX_USAGE_ALL_LINES) + " more (showing top " + MAX_USAGE_ALL_LINES + ")");
            }
            send.send(by.uuid(), String.join("\n", lines), SendKind.FINAL);
            return;
        }

        Optional<UsageStore.NameMatch> resolved = resolveTarget(target);
        if (resolved.isEmpty()) {
            send.send(by.uuid(), "Unknown player \"" + target.name() + "\".", SendKind.FINAL);
            return;
        }
        send.send(by.uuid(), fmtUsageBlock(store.summary(resolved.get().uuid(), resolved.get().name())), SendKind.FINAL);
    }

    public void handleLimit(By by, Target target, List<String> args) {
        String uuidOrNull;
        String who;
        String nameForCreate;
        if (target == null) {
            uuidOrNull = null;
            who = "the server default";
            nameForCreate = null;
        } else {
            Optional<UsageStore.NameMatch> resolved = resolveTarget(target);
            if (resolved.isEmpty()) {
                send.send(by.uuid(), "Unknown player \"" + target.name() + "\".", SendKind.FINAL);
                return;
            }
            uuidOrNull = resolved.get().uuid();
            who = resolved.get().name();
            nameForCreate = resolved.get().name();
        }

        if (!args.isEmpty() && "reset".equals(args.get(0))) {
            store.resetLimits(uuidOrNull);
            send.send(by.uuid(), "Reset limits for " + who + ": " + fmtLimitsLine(store.effectiveLimits(uuidOrNull), null), SendKind.FINAL);
            return;
        }

        String kindRaw = !args.isEmpty() ? args.get(0) : null;
        if (!"cost".equals(kindRaw) && !"tokens".equals(kindRaw) && !"requests".equals(kindRaw)) {
            send.send(by.uuid(), "Unknown limit kind \"" + (kindRaw != null ? kindRaw : "") + "\". Use cost, tokens, requests, or reset.", SendKind.FINAL);
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
                send.send(by.uuid(), "Limit value must be a positive number or \"off\" (got \"" + (rawValue != null ? rawValue : "") + "\").", SendKind.FINAL);
                return;
            }
            value = UsageStore.LimitValue.of(n);
        }

        store.setLimit(uuidOrNull, kind, value, nameForCreate);
        send.send(by.uuid(), "Set limits for " + who + ": " + fmtLimitsLine(store.effectiveLimits(uuidOrNull), null), SendKind.FINAL);
    }

    public void handleCancel(By by, Target target) {
        if (target == null) {
            send.send(by.uuid(), "cancel needs a target player.", SendKind.FINAL);
            return;
        }
        String uuid = target.uuid() != null ? target.uuid() : store.findByName(target.name()).map(UsageStore.NameMatch::uuid).orElse(null);
        CancelOutcome outcome = uuid != null ? cancel.cancel(uuid) : CancelOutcome.NONE;
        if (outcome == CancelOutcome.NONE) {
            send.send(by.uuid(), target.name() + " has no request running.", SendKind.FINAL);
            return;
        }
        send.send(by.uuid(), "Cancelled " + target.name() + "'s request.", SendKind.FINAL);
        send.send(uuid, "Your request was cancelled by " + by.name() + ".", SendKind.FINAL);
    }

    public void handlePauseResume(By by, boolean resume) {
        store.setPaused(!resume);
        String message = resume ? "Assistant resumed." : "Assistant paused. New requests are rejected until /ashlar resume.";
        send.send(by.uuid(), message, SendKind.FINAL);
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

    private String fmtUsageBlock(UsageStore.UsageSummary s) {
        String todayTokens = UsageStore.fmtTokens(s.today().inputTokens + s.today().cachedInputTokens + s.today().outputTokens);
        String totalTokens = UsageStore.fmtTokens(s.total().inputTokens + s.total().cachedInputTokens + s.total().outputTokens);
        return String.join("\n", List.of(
                "Usage for " + s.name() + ":",
                "today: " + s.today().requests + " requests, " + todayTokens + " tokens, " + UsageStore.fmtCost(s.today().cost, currency),
                "total: " + s.total().requests + " requests, " + totalTokens + " tokens, " + UsageStore.fmtCost(s.total().cost, currency),
                "limits: " + fmtLimitsLine(s.limits(), s.overrides())));
    }

    private String fmtLimitValue(UsageStore.LimitKind kind, UsageStore.LimitValue value) {
        if (value.isOff()) {
            return "unlimited " + kind.wire();
        }
        if (kind == UsageStore.LimitKind.COST) {
            return UsageStore.fmtCost(value.amount(), currency) + "/day";
        }
        if (kind == UsageStore.LimitKind.TOKENS) {
            return UsageStore.fmtTokens(value.amount()) + " tokens/day";
        }
        return formatWholeNumber(value.amount()) + " requests/day";
    }

    private String fmtLimitsLine(Map<UsageStore.LimitKind, UsageStore.LimitValue> limits, Map<UsageStore.LimitKind, Boolean> overrides) {
        List<String> parts = new ArrayList<>();
        for (UsageStore.LimitKind kind : LIMIT_KINDS) {
            String text = fmtLimitValue(kind, limits.get(kind));
            boolean isOverride = overrides != null && Boolean.TRUE.equals(overrides.get(kind));
            parts.add(isOverride ? text + " (override)" : text);
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
