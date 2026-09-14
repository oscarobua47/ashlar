// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { test } from "node:test";

import { isPeak, parsePeakHours, priceMultiplier } from "./pricing.js";

// A Monday and a Saturday, UTC.
function utc(y: number, mo: number, d: number, h: number, mi: number): Date {
    return new Date(Date.UTC(y, mo - 1, d, h, mi));
}

test("parsePeakHours: the default DeepSeek schedule", () => {
    const schedule = parsePeakHours("mon-fri 01:00-04:00,06:00-10:00");
    assert.equal(schedule.always, false);
    assert.equal(schedule.days, "mon-fri");
    assert.deepEqual(schedule.windows, [
        { startMin: 60, endMin: 240 },
        { startMin: 360, endMin: 600 }
    ]);
});

test("parsePeakHours: 'always' disables the discount", () => {
    const schedule = parsePeakHours("always");
    assert.equal(schedule.always, true);
    assert.equal(isPeak(schedule, utc(2026, 9, 12, 12, 0)), true); // a Saturday noon
});

test("parsePeakHours: day range defaults to daily when omitted", () => {
    const schedule = parsePeakHours("09:00-17:00");
    assert.equal(schedule.days, "daily");
    assert.deepEqual(schedule.windows, [{ startMin: 540, endMin: 1020 }]);
});

test("parsePeakHours: 'sat-sun' and 'daily' are accepted explicitly", () => {
    assert.equal(parsePeakHours("sat-sun 00:00-23:59").days, "sat-sun");
    assert.equal(parsePeakHours("daily 00:00-23:59").days, "daily");
});

test("parsePeakHours: rejects malformed windows", () => {
    assert.throws(() => parsePeakHours("mon-fri 1:00-4:00"));
    assert.throws(() => parsePeakHours("mon-fri"));
    assert.throws(() => parsePeakHours(""));
});

test("isPeak: boundary times around a 01:00-04:00 window (mon-fri)", () => {
    const schedule = parsePeakHours("mon-fri 01:00-04:00,06:00-10:00");
    // 2026-09-14 is a Monday.
    assert.equal(isPeak(schedule, utc(2026, 9, 14, 0, 59)), false, "00:59 is before the window");
    assert.equal(isPeak(schedule, utc(2026, 9, 14, 1, 0)), true, "01:00 is the start, inclusive");
    assert.equal(isPeak(schedule, utc(2026, 9, 14, 3, 59)), true, "03:59 is still inside the window");
    assert.equal(isPeak(schedule, utc(2026, 9, 14, 4, 0)), false, "04:00 is the end, exclusive");
});

test("isPeak: a Saturday inside the time-of-day window is off-peak (mon-fri only)", () => {
    const schedule = parsePeakHours("mon-fri 01:00-04:00,06:00-10:00");
    // 2026-09-12 is a Saturday.
    assert.equal(isPeak(schedule, utc(2026, 9, 12, 2, 0)), false);
});

test("isPeak: a wrapping window (22:00-02:00) straddles midnight", () => {
    const schedule = parsePeakHours("daily 22:00-02:00");
    assert.equal(isPeak(schedule, utc(2026, 9, 14, 23, 0)), true, "23:00 is inside the wrapped window");
    assert.equal(isPeak(schedule, utc(2026, 9, 15, 1, 0)), true, "01:00 the next day is still inside");
    assert.equal(isPeak(schedule, utc(2026, 9, 15, 2, 0)), false, "02:00 is the end, exclusive");
    assert.equal(isPeak(schedule, utc(2026, 9, 14, 21, 59)), false, "21:59 is before the window");
});

test("priceMultiplier: 1 during peak, offPeakMultiplier outside it", () => {
    const schedule = parsePeakHours("mon-fri 01:00-04:00,06:00-10:00");
    assert.equal(priceMultiplier(schedule, 0.5, utc(2026, 9, 14, 2, 0)), 1);
    assert.equal(priceMultiplier(schedule, 0.5, utc(2026, 9, 14, 12, 0)), 0.5);
});
