// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Port of {@code mcp-server/src/agent/pricing.test.ts}. */
class PricingTest {

    // A Monday and a Saturday, UTC.
    private static Instant utc(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant();
    }

    @Test
    void parsePeakHoursDefaultDeepSeekSchedule() {
        Pricing.Schedule schedule = Pricing.parsePeakHours("mon-fri 01:00-04:00,06:00-10:00");
        assertFalse(schedule.always());
        assertEquals(Pricing.DayRange.MON_FRI, schedule.days());
        assertEquals(2, schedule.windows().size());
        assertEquals(new Pricing.Window(60, 240), schedule.windows().get(0));
        assertEquals(new Pricing.Window(360, 600), schedule.windows().get(1));
    }

    @Test
    void parsePeakHoursAlwaysDisablesDiscount() {
        Pricing.Schedule schedule = Pricing.parsePeakHours("always");
        assertTrue(schedule.always());
        assertTrue(Pricing.isPeak(schedule, utc(2026, 9, 12, 12, 0))); // a Saturday noon
    }

    @Test
    void parsePeakHoursDayRangeDefaultsToDaily() {
        Pricing.Schedule schedule = Pricing.parsePeakHours("09:00-17:00");
        assertEquals(Pricing.DayRange.DAILY, schedule.days());
        assertEquals(1, schedule.windows().size());
        assertEquals(new Pricing.Window(540, 1020), schedule.windows().get(0));
    }

    @Test
    void parsePeakHoursSatSunAndDailyAcceptedExplicitly() {
        assertEquals(Pricing.DayRange.SAT_SUN, Pricing.parsePeakHours("sat-sun 00:00-23:59").days());
        assertEquals(Pricing.DayRange.DAILY, Pricing.parsePeakHours("daily 00:00-23:59").days());
    }

    @Test
    void parsePeakHoursRejectsMalformedWindows() {
        assertThrows(IllegalArgumentException.class, () -> Pricing.parsePeakHours("mon-fri 1:00-4:00"));
        assertThrows(IllegalArgumentException.class, () -> Pricing.parsePeakHours("mon-fri"));
        assertThrows(IllegalArgumentException.class, () -> Pricing.parsePeakHours(""));
    }

    @Test
    void isPeakBoundaryTimesAroundWindow() {
        Pricing.Schedule schedule = Pricing.parsePeakHours("mon-fri 01:00-04:00,06:00-10:00");
        // 2026-09-14 is a Monday.
        assertFalse(Pricing.isPeak(schedule, utc(2026, 9, 14, 0, 59)), "00:59 is before the window");
        assertTrue(Pricing.isPeak(schedule, utc(2026, 9, 14, 1, 0)), "01:00 is the start, inclusive");
        assertTrue(Pricing.isPeak(schedule, utc(2026, 9, 14, 3, 59)), "03:59 is still inside the window");
        assertFalse(Pricing.isPeak(schedule, utc(2026, 9, 14, 4, 0)), "04:00 is the end, exclusive");
    }

    @Test
    void isPeakSaturdayInsideWindowIsOffPeakMonFriOnly() {
        Pricing.Schedule schedule = Pricing.parsePeakHours("mon-fri 01:00-04:00,06:00-10:00");
        // 2026-09-12 is a Saturday.
        assertFalse(Pricing.isPeak(schedule, utc(2026, 9, 12, 2, 0)));
    }

    @Test
    void isPeakWrappingWindowStraddlesMidnight() {
        Pricing.Schedule schedule = Pricing.parsePeakHours("daily 22:00-02:00");
        assertTrue(Pricing.isPeak(schedule, utc(2026, 9, 14, 23, 0)), "23:00 is inside the wrapped window");
        assertTrue(Pricing.isPeak(schedule, utc(2026, 9, 15, 1, 0)), "01:00 the next day is still inside");
        assertFalse(Pricing.isPeak(schedule, utc(2026, 9, 15, 2, 0)), "02:00 is the end, exclusive");
        assertFalse(Pricing.isPeak(schedule, utc(2026, 9, 14, 21, 59)), "21:59 is before the window");
    }

    @Test
    void priceMultiplierPeakVsOffPeak() {
        Pricing.Schedule schedule = Pricing.parsePeakHours("mon-fri 01:00-04:00,06:00-10:00");
        assertEquals(1, Pricing.multiplier(schedule, 0.5, utc(2026, 9, 14, 2, 0)));
        assertEquals(0.5, Pricing.multiplier(schedule, 0.5, utc(2026, 9, 14, 12, 0)));
    }
}
