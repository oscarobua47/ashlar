// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import net.rcwalter.ashlar.agent.model.Usage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Port of {@code mcp-server/src/agent/admin.test.ts}. */
class AdminActionsTest {

    @TempDir
    Path tempDir;

    private UsageStore store;

    private static final AdminActions.By BY = new AdminActions.By("Op", "op-uuid");

    private record Sent(String uuid, String text, AdminActions.SendKind kind) {
    }

    private UsageStore newStore() {
        store = new UsageStore(tempDir.resolve("usage.json"), 0.3, 0.006, 1.2, "USD",
                new UsageStore.Limits(0, 0, 40), Pricing.parsePeakHours("always"), 0.5, java.time.Instant::now, 0);
        return store;
    }

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    private static List<Sent> newSent() {
        return new ArrayList<>();
    }

    private static AdminActions.Send fakeSend(List<Sent> sent) {
        return (uuid, text, kind) -> sent.add(new Sent(uuid, text, kind));
    }

    @Test
    void usageSelfNullTargetReportsCallersOwnUsage() {
        UsageStore s = newStore();
        s.record(BY.uuid(), BY.name(), new Usage(1000, 0, 100));
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, null, List.of());

        assertEquals(1, sent.size());
        assertEquals(BY.uuid(), sent.get(0).uuid());
        assertTrue(sent.get(0).text().contains("Usage for Op:"));
        assertTrue(sent.get(0).text().contains("today: 1 requests"));
        assertEquals(AdminActions.SendKind.FINAL, sent.get(0).kind());
    }

    @Test
    void usageNamedPlayerResolvedByUuid() {
        UsageStore s = newStore();
        s.record("alex-uuid", "Alex", new Usage(500, 0, 50));
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of());

        assertTrue(sent.get(0).text().contains("Usage for Alex:"));
    }

    @Test
    void usageOfflineNamedPlayerResolvedByLastSeenName() {
        UsageStore s = newStore();
        s.record("alex-uuid", "Alex", new Usage(500, 0, 50));
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, new AdminActions.Target("alex", null), List.of());

        assertTrue(sent.get(0).text().contains("Usage for Alex:"));
    }

    @Test
    void usageUnknownPlayerTellsCaller() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, new AdminActions.Target("Nobody", null), List.of());

        assertTrue(sent.get(0).text().contains("Unknown player \"Nobody\""));
    }

    @Test
    void usageAllListsEveryPlayerSortedByTodayCost() {
        UsageStore s = newStore();
        s.record("u1", "Cheap", new Usage(100, 0, 10));
        s.record("u2", "Pricey", new Usage(1_000_000, 0, 1_000_000));
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, new AdminActions.Target("all", null), List.of());

        String text = sent.get(0).text();
        int priceyIndex = text.indexOf("Pricey");
        int cheapIndex = text.indexOf("Cheap");
        assertTrue(priceyIndex >= 0 && cheapIndex >= 0 && priceyIndex < cheapIndex, "Pricey (higher cost) must be listed first");
    }

    @Test
    void limitSetsPerPlayerOverrideAndRepliesWithNewEffectiveLimits() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("limit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("cost", "5"));

        assertEquals(5.0, s.effectiveLimit("alex-uuid", UsageStore.LimitKind.COST).amount());
        assertTrue(sent.get(0).text().contains("Set limits for Alex"));
        assertTrue(sent.get(0).text().contains("$5.00/day"));
    }

    @Test
    void limitNullTargetSetsServerDefault() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("limit", BY, null, List.of("tokens", "10000"));

        assertEquals(10_000.0, s.effectiveLimits(null).get(UsageStore.LimitKind.TOKENS).amount());
        assertTrue(sent.get(0).text().contains("Set limits for the server default"));
    }

    @Test
    void limitOffSetsUnlimited() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("limit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("requests", "off"));

        assertTrue(s.effectiveLimit("alex-uuid", UsageStore.LimitKind.REQUESTS).isOff());
    }

    @Test
    void limitResetRemovesTheOverride() {
        UsageStore s = newStore();
        s.setLimit("alex-uuid", UsageStore.LimitKind.COST, UsageStore.LimitValue.of(5), "Alex");
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("limit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("reset"));

        assertTrue(s.effectiveLimit("alex-uuid", UsageStore.LimitKind.COST).isOff());
        assertTrue(sent.get(0).text().contains("Reset limits for Alex"));
    }

    @Test
    void limitNonPositiveValueIsRejectedNotApplied() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("limit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("cost", "-1"));

        assertTrue(sent.get(0).text().contains("must be a positive number"));
        assertTrue(s.effectiveLimit("alex-uuid", UsageStore.LimitKind.COST).isOff());
    }

    @Test
    void limitUnknownKindIsRejected() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("limit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("bogus", "5"));

        assertTrue(sent.get(0).text().contains("Unknown limit kind"));
    }

    @Test
    void creditShowReportsNoLimitWhenNeverEnabled() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("credit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of());

        assertTrue(sent.get(0).text().contains("Alex has no credit limit."));
    }

    @Test
    void creditShowReportsBalanceWhenEnabled() {
        UsageStore s = newStore();
        s.addCredit("alex-uuid", "Alex", 3.2);
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("credit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of());

        assertTrue(sent.get(0).text().contains("Credit for Alex: $3.20 left"));
    }

    @Test
    void creditAddEnablesAndReportsNewBalance() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("credit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("add", "5"));

        assertEquals(5.0, s.creditOf("alex-uuid").orElseThrow().balance());
        assertTrue(sent.get(0).text().contains("Credit for Alex: $5.00 (added $5.00)"), "unexpected reply: " + sent.get(0).text());

        sent.clear();
        admin.handle("credit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("add", "2.5"));
        assertTrue(sent.get(0).text().contains("Credit for Alex: $7.50 (added $2.50)"), "unexpected reply: " + sent.get(0).text());
    }

    @Test
    void creditSetReplacesBalanceOutright() {
        UsageStore s = newStore();
        s.addCredit("alex-uuid", "Alex", 100);
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("credit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("set", "5"));

        assertEquals(5.0, s.creditOf("alex-uuid").orElseThrow().balance());
        assertTrue(sent.get(0).text().contains("Credit for Alex: $5.00"));
        assertFalse(sent.get(0).text().contains("added"));
    }

    @Test
    void creditSetNonPositiveIsRejectedNotApplied() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("credit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("set", "0"));

        assertTrue(sent.get(0).text().contains("must be a positive number"));
        assertTrue(s.creditOf("alex-uuid").isEmpty());
    }

    @Test
    void creditAddNonNumberIsRejected() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("credit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("add", "not-a-number"));

        assertTrue(sent.get(0).text().contains("must be a number"));
        assertTrue(s.creditOf("alex-uuid").isEmpty());
    }

    @Test
    void creditOffDisablesIt() {
        UsageStore s = newStore();
        s.addCredit("alex-uuid", "Alex", 5);
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("credit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("off"));

        assertTrue(sent.get(0).text().contains("Credit disabled for Alex."));
        assertTrue(s.creditOf("alex-uuid").isEmpty());
    }

    @Test
    void creditUnknownActionIsRejected() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("credit", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("bogus"));

        assertTrue(sent.get(0).text().contains("Unknown credit action"));
    }

    @Test
    void creditUnknownPlayerTellsCaller() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("credit", BY, new AdminActions.Target("Nobody", null), List.of());

        assertTrue(sent.get(0).text().contains("Unknown player \"Nobody\""));
    }

    @Test
    void creditUsageBlockShowsCreditLineOnlyWhenEnabled() {
        UsageStore s = newStore();
        s.record(BY.uuid(), BY.name(), new Usage(1000, 0, 100));
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, null, List.of());
        assertFalse(sent.get(0).text().contains("credit:"), "no credit line when credit is not enabled");

        s.addCredit(BY.uuid(), BY.name(), 3.2);
        sent.clear();
        admin.handle("usage", BY, null, List.of());
        assertTrue(sent.get(0).text().contains("credit: $3.20 left"));
    }

    @Test
    void cancelRunningReportsSuccessAndNotifiesTargetNoneReportsFailure() {
        UsageStore s = newStore();
        Map<String, AdminActions.CancelOutcome> outcomes = Map.of("alex-uuid", AdminActions.CancelOutcome.RUNNING, "steve-uuid", AdminActions.CancelOutcome.NONE);
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> outcomes.getOrDefault(uuid, AdminActions.CancelOutcome.NONE), fakeSend(sent), "USD");

        admin.handle("cancel", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of());
        assertTrue(sent.get(0).text().contains("Cancelled Alex's request."));
        assertEquals("alex-uuid", sent.get(1).uuid());
        assertTrue(sent.get(1).text().contains("cancelled by Op"));

        sent.clear();
        admin.handle("cancel", BY, new AdminActions.Target("Steve", "steve-uuid"), List.of());
        assertTrue(sent.get(0).text().contains("Steve has no request running."));
        assertEquals(1, sent.size(), "no notification is sent when there was nothing to cancel");
    }

    @Test
    void pauseResumeSetsStorePauseFlagAndReplies() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("pause", BY, null, List.of());
        assertTrue(s.isPaused());
        assertTrue(sent.get(0).text().toLowerCase(java.util.Locale.ROOT).contains("paused"));

        admin.handle("resume", BY, null, List.of());
        assertTrue(!s.isPaused());
        assertTrue(sent.get(1).text().toLowerCase(java.util.Locale.ROOT).contains("resumed"));
    }

    // ---- usage range report (step8g-prompt.md) ----

    private UsageStore newStore(java.time.Instant now) {
        store = new UsageStore(tempDir.resolve("usage.json"), 0.3, 0.006, 1.2, "USD",
                new UsageStore.Limits(0, 0, 40), Pricing.parsePeakHours("always"), 0.5, () -> now, 0);
        return store;
    }

    @Test
    void usageSelfLastSevenDaysShowsOneLinePerDayAndATotal() {
        java.time.Instant now = java.time.ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant();
        UsageStore s = newStore(now);
        s.record(BY.uuid(), BY.name(), new Usage(1000, 0, 100));
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, null, List.of("7"));

        String text = sent.get(0).text();
        assertTrue(text.contains("Usage for Op, 2026-09-08..2026-09-14:"), "unexpected header: " + text);
        assertTrue(text.contains("2026-09-14  1 req"), "unexpected day line: " + text);
        assertTrue(text.contains("2026-09-08  0 req  0 tok  $0.00"), "unexpected zero day: " + text);
        assertTrue(text.contains("total: 1 req,"), "unexpected total line: " + text);
        String[] lines = text.split("\n");
        assertEquals(9, lines.length, "header + 7 days + total: " + text); // 1 header + 7 day rows + 1 total
    }

    @Test
    void usageExplicitDateRangeIsRespected() {
        java.time.Instant now = java.time.ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant();
        UsageStore s = newStore(now);
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, null, List.of("2026-09-01", "2026-09-03"));

        String text = sent.get(0).text();
        assertTrue(text.contains("Usage for Op, 2026-09-01..2026-09-03:"), "unexpected header: " + text);
        String[] lines = text.split("\n");
        assertEquals(5, lines.length, "header + 3 days + total: " + text);
    }

    @Test
    void usageReversedDateRangeIsSwapped() {
        java.time.Instant now = java.time.ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant();
        UsageStore s = newStore(now);
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, null, List.of("2026-09-03", "2026-09-01"));

        assertTrue(sent.get(0).text().contains("Usage for Op, 2026-09-01..2026-09-03:"), "unexpected header: " + sent.get(0).text());
    }

    @Test
    void usageDateRangeOverThirtyOneDaysIsAnError() {
        java.time.Instant now = java.time.ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant();
        UsageStore s = newStore(now);
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, null, List.of("2026-08-01", "2026-09-05"));

        assertTrue(sent.get(0).text().contains("too long"), "unexpected reply: " + sent.get(0).text());
    }

    @Test
    void usageFutureDatesClampToToday() {
        java.time.Instant now = java.time.ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant();
        UsageStore s = newStore(now);
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, null, List.of("2026-09-12", "2026-09-30"));

        assertTrue(sent.get(0).text().contains("Usage for Op, 2026-09-12..2026-09-14:"), "unexpected header: " + sent.get(0).text());
    }

    @Test
    void usageBadDaysCountIsAnError() {
        java.time.Instant now = java.time.ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant();
        UsageStore s = newStore(now);
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, null, List.of("40"));

        assertTrue(sent.get(0).text().contains("1-31"), "unexpected reply: " + sent.get(0).text());
    }

    @Test
    void usageRangeForNamedPlayerReportsThatPlayersHistory() {
        java.time.Instant now = java.time.ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant();
        UsageStore s = newStore(now);
        s.record("alex-uuid", "Alex", new Usage(1000, 0, 100));
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, new AdminActions.Target("Alex", "alex-uuid"), List.of("7"));

        assertTrue(sent.get(0).text().contains("Usage for Alex, 2026-09-08..2026-09-14:"), "unexpected header: " + sent.get(0).text());
    }

    @Test
    void usageRangeForUnknownPlayerTellsCaller() {
        java.time.Instant now = java.time.ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant();
        UsageStore s = newStore(now);
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, new AdminActions.Target("Nobody", null), List.of("7"));

        assertTrue(sent.get(0).text().contains("Unknown player \"Nobody\""));
    }

    @Test
    void usageRangeForAllSumsServerWidePerDay() {
        java.time.Instant now = java.time.ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant();
        UsageStore s = newStore(now);
        s.record("u1", "Cheap", new Usage(100, 0, 10));
        s.record("u2", "Pricey", new Usage(1_000_000, 0, 1_000_000));
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("usage", BY, new AdminActions.Target("all", null), List.of("7"));

        String text = sent.get(0).text();
        assertTrue(text.contains("Usage for all, 2026-09-08..2026-09-14:"), "unexpected header: " + text);
        assertTrue(text.contains("2026-09-14  2 req"), "unexpected server-wide day line: " + text);
        assertTrue(text.contains("total: 2 req,"), "unexpected total line: " + text);
    }

    @Test
    void unknownActionIsIgnoredWithoutSendingAnything() {
        UsageStore s = newStore();
        List<Sent> sent = newSent();
        AdminActions admin = new AdminActions(s, uuid -> AdminActions.CancelOutcome.NONE, fakeSend(sent), "USD");

        admin.handle("teleport", BY, null, List.of());

        assertEquals(0, sent.size());
    }
}
