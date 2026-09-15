// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.rcwalter.ashlar.agent.model.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Port of {@code mcp-server/src/agent/usage.test.ts}. */
class UsageStoreTest {

    @TempDir
    Path tempDir;

    // A Monday 02:00 UTC (peak, per the default schedule) and a Monday 12:00 UTC (off-peak).
    private static final Instant PEAK_TIME = ZonedDateTime.of(2026, 9, 14, 2, 0, 0, 0, ZoneOffset.UTC).toInstant();
    private static final Instant OFF_PEAK_TIME = ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, ZoneOffset.UTC).toInstant();

    private Path filePath() {
        return tempDir.resolve("usage.json");
    }

    private UsageStore newStore(Instant now, UsageStore.Limits envLimits) {
        return new UsageStore(filePath(), 0.3, 0.006, 1.2, "USD", envLimits,
                Pricing.parsePeakHours("mon-fri 01:00-04:00,06:00-10:00"), 0.5, () -> now, 0);
    }

    private UsageStore newStore(AtomicReference<Instant> now, UsageStore.Limits envLimits) {
        return new UsageStore(filePath(), 0.3, 0.006, 1.2, "USD", envLimits,
                Pricing.parsePeakHours("mon-fri 01:00-04:00,06:00-10:00"), 0.5, now::get, 0);
    }

    private static final UsageStore.Limits DEFAULT_ENV_LIMITS = new UsageStore.Limits(0, 0, 40);

    @Test
    void recordCostArithmeticAtPeakPrice() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            UsageStore.RecordResult result = store.record("u1", "Alex", new Usage(1_000_000, 1_000_000, 1_000_000));
            // 1M miss * 0.30 + 1M hit * 0.006 + 1M out * 1.20, all at full (peak) price.
            assertTrue(Math.abs(result.cost() - (0.3 + 0.006 + 1.2)) < 1e-9);
            assertEquals(1, result.today().requests);
            assertEquals(1_000_000, result.today().inputTokens);
            assertEquals(1_000_000, result.today().cachedInputTokens);
            assertEquals(1_000_000, result.today().outputTokens);
        }
    }

    @Test
    void recordOffPeakMultiplierHalvesCost() {
        try (UsageStore store = newStore(OFF_PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            UsageStore.RecordResult result = store.record("u1", "Alex", new Usage(1_000_000, 1_000_000, 1_000_000));
            assertTrue(Math.abs(result.cost() - (0.3 + 0.006 + 1.2) * 0.5) < 1e-9);
        }
    }

    @Test
    void recordAccumulatesIntoTodayAndTotal() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            store.record("u1", "Alex", new Usage(100, 0, 10));
            UsageStore.RecordResult second = store.record("u1", "Alex", new Usage(200, 0, 20));
            assertEquals(2, second.today().requests);
            assertEquals(300, second.today().inputTokens);
            assertEquals(2, second.total().requests);
            assertEquals(300, second.total().inputTokens);
        }
    }

    @Test
    void dayRolloverResetsTodayNotTotal() {
        AtomicReference<Instant> now = new AtomicReference<>(ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, ZoneOffset.UTC).toInstant());
        try (UsageStore store = newStore(now, DEFAULT_ENV_LIMITS)) {
            store.record("u1", "Alex", new Usage(100, 0, 10));

            now.set(ZonedDateTime.of(2026, 9, 15, 1, 0, 0, 0, ZoneOffset.UTC).toInstant());
            UsageStore.RecordResult result = store.record("u1", "Alex", new Usage(50, 0, 5));

            assertEquals("2026-09-15", result.today().date);
            assertEquals(1, result.today().requests);
            assertEquals(50, result.today().inputTokens);
            assertEquals(2, result.total().requests);
            assertEquals(150, result.total().inputTokens);
        }
    }

    @Test
    void checkAllowedRollsOverLazilyAndDoesNotBlockFreshDay() {
        AtomicReference<Instant> now = new AtomicReference<>(ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, ZoneOffset.UTC).toInstant());
        try (UsageStore store = newStore(now, new UsageStore.Limits(0, 0, 1))) {
            store.record("u1", "Alex", new Usage(10, 0, 1));
            assertFalse(store.checkAllowed("u1").ok());

            now.set(ZonedDateTime.of(2026, 9, 15, 12, 0, 0, 0, ZoneOffset.UTC).toInstant());
            assertTrue(store.checkAllowed("u1").ok());
        }
    }

    @Test
    void limitPrecedencePlayerOverStoreDefaultOverEnvDefault() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            assertEquals(40.0, store.effectiveLimit("u1", UsageStore.LimitKind.REQUESTS).amount());

            store.setLimit(null, UsageStore.LimitKind.REQUESTS, UsageStore.LimitValue.of(20), null);
            assertEquals(20.0, store.effectiveLimit("u1", UsageStore.LimitKind.REQUESTS).amount());

            store.setLimit("u1", UsageStore.LimitKind.REQUESTS, UsageStore.LimitValue.of(5), "Alex");
            assertEquals(5.0, store.effectiveLimit("u1", UsageStore.LimitKind.REQUESTS).amount());

            store.resetLimits("u1");
            assertEquals(20.0, store.effectiveLimit("u1", UsageStore.LimitKind.REQUESTS).amount());

            store.resetLimits(null);
            assertEquals(40.0, store.effectiveLimit("u1", UsageStore.LimitKind.REQUESTS).amount());
        }
    }

    @Test
    void limitOffMeansUnlimitedAndCheckAllowedNeverRejects() {
        try (UsageStore store = newStore(PEAK_TIME, new UsageStore.Limits(0, 0, 1))) {
            store.record("u1", "Alex", new Usage(10, 0, 1));
            assertFalse(store.checkAllowed("u1").ok());

            store.setLimit("u1", UsageStore.LimitKind.REQUESTS, UsageStore.LimitValue.OFF, null);
            assertTrue(store.effectiveLimit("u1", UsageStore.LimitKind.REQUESTS).isOff());
            assertTrue(store.checkAllowed("u1").ok());
        }
    }

    @Test
    void checkAllowedReportsFirstExceededLimitByName() {
        try (UsageStore store = newStore(PEAK_TIME, new UsageStore.Limits(1, 500_000, 40))) {
            store.record("u1", "Alex", new Usage(400_000, 0, 200_000));
            UsageStore.CheckResult allowed = store.checkAllowed("u1");
            assertFalse(allowed.ok());
            assertTrue(allowed.reason().matches(".*daily token limit \\(500k\\) reached.*"));
        }
    }

    @Test
    void persistenceRoundTripsThroughRealFile() throws IOException {
        Path filePath = filePath();
        try (UsageStore store1 = new UsageStore(filePath, 0.3, 0.006, 1.2, "USD", DEFAULT_ENV_LIMITS,
                Pricing.parsePeakHours("mon-fri 01:00-04:00,06:00-10:00"), 0.5, () -> PEAK_TIME, 0)) {
            store1.record("u1", "Alex", new Usage(1000, 100, 50));
            store1.setLimit("u1", UsageStore.LimitKind.COST, UsageStore.LimitValue.of(5), null);
            store1.setLimit(null, UsageStore.LimitKind.TOKENS, UsageStore.LimitValue.of(10_000), null);
            store1.setPaused(true);
        }

        assertTrue(Files.exists(filePath));
        JsonObject raw = JsonParser.parseString(Files.readString(filePath)).getAsJsonObject();
        assertEquals(1, raw.get("version").getAsInt());

        try (UsageStore store2 = new UsageStore(filePath, 0.3, 0.006, 1.2, "USD", DEFAULT_ENV_LIMITS,
                Pricing.parsePeakHours("mon-fri 01:00-04:00,06:00-10:00"), 0.5, () -> PEAK_TIME, 0)) {
            assertTrue(store2.isPaused());
            assertEquals(5.0, store2.effectiveLimit("u1", UsageStore.LimitKind.COST).amount());
            assertEquals(10_000.0, store2.effectiveLimit("u2", UsageStore.LimitKind.TOKENS).amount());
            UsageStore.UsageSummary summary = store2.summary("u1");
            assertEquals("Alex", summary.name());
            assertEquals(1000, summary.total().inputTokens);
            assertEquals(100, summary.total().cachedInputTokens);
            assertEquals(50, summary.total().outputTokens);
        }
    }

    @Test
    void corruptFileIsBackedUpAndStoreStartsEmpty() throws IOException {
        Path filePath = filePath();
        Files.writeString(filePath, "{ this is not valid JSON");
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            assertFalse(store.isPaused());
            assertEquals(0, store.summaryAll().size());
        }
        boolean hasBackup;
        try (var stream = Files.list(tempDir)) {
            hasBackup = stream.anyMatch(p -> p.getFileName().toString().contains("usage.json.corrupt-"));
        }
        assertTrue(hasBackup);
    }

    @Test
    void missingFileStoreStartsEmptyWithoutError() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            assertFalse(store.isPaused());
            assertEquals(0, store.summaryAll().size());
        }
    }

    @Test
    void findByNameCaseInsensitivePrefersMostRecentlyUpdated() {
        AtomicReference<Instant> now = new AtomicReference<>(ZonedDateTime.of(2026, 9, 14, 1, 0, 0, 0, ZoneOffset.UTC).toInstant());
        try (UsageStore store = newStore(now, DEFAULT_ENV_LIMITS)) {
            store.record("u1", "Alex", new Usage(1, 0, 1));

            now.set(ZonedDateTime.of(2026, 9, 14, 2, 0, 0, 0, ZoneOffset.UTC).toInstant());
            store.record("u2", "alex", new Usage(1, 0, 1));

            Optional<UsageStore.NameMatch> found = store.findByName("ALEX");
            assertTrue(found.isPresent());
            assertEquals("u2", found.get().uuid());
            assertEquals("alex", found.get().name());

            assertTrue(store.findByName("nobody").isEmpty());
        }
    }

    @Test
    void fmtTokensPlainBelow1000KBelow1MTrailingZeroDroppedMAbove() {
        assertEquals("0", UsageStore.fmtTokens(0));
        assertEquals("999", UsageStore.fmtTokens(999));
        assertEquals("21.9k", UsageStore.fmtTokens(21_900));
        assertEquals("500k", UsageStore.fmtTokens(500_000));
        assertEquals("1.2M", UsageStore.fmtTokens(1_200_000));
    }

    @Test
    void fmtCostTwoDecimalsNormallyFourWhenBelowPoint01CurrencyPrefix() {
        assertEquals("$0.00", UsageStore.fmtCost(0, "USD"));
        assertEquals("$1.00", UsageStore.fmtCost(1, "USD"));
        assertEquals("$0.04", UsageStore.fmtCost(0.04, "USD"));
        assertEquals("$0.0061", UsageStore.fmtCost(0.0061, "USD"));
        assertEquals("CNY 0.04", UsageStore.fmtCost(0.04, "CNY"));
    }

    // ---- credit ----

    @Test
    void addCreditEnablesAndAccumulates() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            assertTrue(store.creditOf("u1").isEmpty());

            double after1 = store.addCredit("u1", "Alex", 5);
            assertEquals(5.0, after1);
            assertTrue(store.creditOf("u1").isPresent());
            assertTrue(store.creditOf("u1").get().enabled());
            assertEquals(5.0, store.creditOf("u1").get().balance());

            double after2 = store.addCredit("u1", "Alex", 2.5);
            assertEquals(7.5, after2);
            assertEquals(7.5, store.creditOf("u1").get().balance());
        }
    }

    @Test
    void addCreditNegativeCorrectionClampsAtZero() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            store.addCredit("u1", "Alex", 2);
            double after = store.addCredit("u1", "Alex", -10);
            assertEquals(0.0, after);
            assertEquals(0.0, store.creditOf("u1").get().balance());
        }
    }

    @Test
    void setCreditReplacesBalanceOutright() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            store.addCredit("u1", "Alex", 100);
            store.setCredit("u1", "Alex", 3.2);
            assertEquals(3.2, store.creditOf("u1").get().balance());
        }
    }

    @Test
    void disableCreditClearsIt() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            store.addCredit("u1", "Alex", 5);
            store.disableCredit("u1");
            assertTrue(store.creditOf("u1").isEmpty());

            // No effect on a player who never had credit, or who is not yet known.
            store.disableCredit("never-seen");
            assertTrue(store.creditOf("never-seen").isEmpty());
        }
    }

    @Test
    void recordDeductsCostFromEnabledBalanceOnly() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            store.addCredit("u1", "Alex", 10);
            store.record("u1", "Alex", new Usage(1_000_000, 0, 0)); // costs $0.30 at peak price
            assertEquals(9.7, store.creditOf("u1").get().balance(), 1e-9);

            // A player without credit is unaffected: record() must not create a credit balance.
            store.record("u2", "Steve", new Usage(1_000_000, 0, 0));
            assertTrue(store.creditOf("u2").isEmpty());
        }
    }

    @Test
    void recordCanDriveBalanceSlightlyNegativeButDisplayShowsZero() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            store.setCredit("u1", "Alex", 0.1);
            store.record("u1", "Alex", new Usage(1_000_000, 0, 0)); // costs $0.30, more than the balance
            assertTrue(store.creditOf("u1").get().balance() < 0);
            assertEquals("$0.00", UsageStore.fmtCredit(store.creditOf("u1").get().balance(), "USD"));
        }
    }

    @Test
    void checkAllowedRejectsWhenCreditIsUsedUp() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            store.setCredit("u1", "Alex", 0);
            UsageStore.CheckResult result = store.checkAllowed("u1");
            assertFalse(result.ok());
            assertTrue(result.reason().contains("out of credit ($0.00 left) - ask an operator to top up"),
                    "unexpected reason: " + result.reason());
        }
    }

    @Test
    void checkAllowedIgnoresCreditForPlayersWithoutIt() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            assertTrue(store.checkAllowed("nobody").ok());
        }
    }

    @Test
    void checkAllowedAllowsWhenCreditIsPositive() {
        try (UsageStore store = newStore(PEAK_TIME, DEFAULT_ENV_LIMITS)) {
            store.setCredit("u1", "Alex", 1);
            assertTrue(store.checkAllowed("u1").ok());
        }
    }

    @Test
    void creditPersistsAcrossRestart() throws IOException {
        Path filePath = filePath();
        try (UsageStore store1 = new UsageStore(filePath, 0.3, 0.006, 1.2, "USD", DEFAULT_ENV_LIMITS,
                Pricing.parsePeakHours("mon-fri 01:00-04:00,06:00-10:00"), 0.5, () -> PEAK_TIME, 0)) {
            store1.addCredit("u1", "Alex", 5);
        }

        JsonObject raw = JsonParser.parseString(Files.readString(filePath)).getAsJsonObject();
        JsonObject playerJson = raw.getAsJsonObject("players").getAsJsonObject("u1");
        assertTrue(playerJson.has("credit"));
        assertTrue(playerJson.getAsJsonObject("credit").get("enabled").getAsBoolean());
        assertEquals(5.0, playerJson.getAsJsonObject("credit").get("balance").getAsDouble());

        try (UsageStore store2 = new UsageStore(filePath, 0.3, 0.006, 1.2, "USD", DEFAULT_ENV_LIMITS,
                Pricing.parsePeakHours("mon-fri 01:00-04:00,06:00-10:00"), 0.5, () -> PEAK_TIME, 0)) {
            assertTrue(store2.creditOf("u1").isPresent());
            assertEquals(5.0, store2.creditOf("u1").get().balance());
            UsageStore.UsageSummary summary = store2.summary("u1");
            assertTrue(summary.credit().isPresent());
            assertEquals(5.0, summary.credit().get().balance());
        }
    }

    @Test
    void creditAbsentFromJsonWhenNeverEnabled() throws IOException {
        Path filePath = filePath();
        try (UsageStore store = new UsageStore(filePath, 0.3, 0.006, 1.2, "USD", DEFAULT_ENV_LIMITS,
                Pricing.parsePeakHours("mon-fri 01:00-04:00,06:00-10:00"), 0.5, () -> PEAK_TIME, 0)) {
            store.record("u1", "Alex", new Usage(10, 0, 1));
        }
        JsonObject raw = JsonParser.parseString(Files.readString(filePath)).getAsJsonObject();
        assertFalse(raw.getAsJsonObject("players").getAsJsonObject("u1").has("credit"));
    }

    @Test
    void fmtCreditClampsNegativeToZero() {
        assertEquals("$0.00", UsageStore.fmtCredit(-1.5, "USD"));
        assertEquals("$3.20", UsageStore.fmtCredit(3.2, "USD"));
    }
}
