// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { parsePeakHours } from "./pricing.js";
import { fmtCost, fmtTokens, UsageStore, type UsageStoreOptions } from "./usage.js";

function tempFile(): { dir: string; filePath: string } {
    const dir = mkdtempSync(join(tmpdir(), "ashlar-usage-test-"));
    return { dir, filePath: join(dir, "usage.json") };
}

function baseOptions(filePath: string, overrides: Partial<UsageStoreOptions> = {}): UsageStoreOptions {
    return {
        filePath,
        priceInput: 0.3,
        priceCachedInput: 0.006,
        priceOutput: 1.2,
        currency: "USD",
        envLimits: { cost: 0, tokens: 0, requests: 40 },
        peakSchedule: parsePeakHours("mon-fri 01:00-04:00,06:00-10:00"),
        offPeakMultiplier: 0.5,
        saveDebounceMs: 0,
        ...overrides
    };
}

// A Monday 02:00 UTC (peak, per the default schedule) and a Monday 12:00 UTC (off-peak).
const PEAK_TIME = new Date(Date.UTC(2026, 8, 14, 2, 0));
const OFF_PEAK_TIME = new Date(Date.UTC(2026, 8, 14, 12, 0));

test("record: cost arithmetic at peak price", () => {
    const { dir, filePath } = tempFile();
    try {
        const store = new UsageStore(baseOptions(filePath, { now: () => PEAK_TIME }));
        const result = store.record("u1", "Alex", { inputTokens: 1_000_000, cachedInputTokens: 1_000_000, outputTokens: 1_000_000 });
        // 1M miss * 0.30 + 1M hit * 0.006 + 1M out * 1.20, all at full (peak) price.
        assert.ok(Math.abs(result.cost - (0.3 + 0.006 + 1.2)) < 1e-9);
        assert.equal(result.today.requests, 1);
        assert.equal(result.today.inputTokens, 1_000_000);
        assert.equal(result.today.cachedInputTokens, 1_000_000);
        assert.equal(result.today.outputTokens, 1_000_000);
        store.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("record: the off-peak multiplier halves the cost", () => {
    const { dir, filePath } = tempFile();
    try {
        const store = new UsageStore(baseOptions(filePath, { now: () => OFF_PEAK_TIME }));
        const result = store.record("u1", "Alex", { inputTokens: 1_000_000, cachedInputTokens: 1_000_000, outputTokens: 1_000_000 });
        assert.ok(Math.abs(result.cost - (0.3 + 0.006 + 1.2) * 0.5) < 1e-9);
        store.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("record: accumulates into both today and total across multiple calls", () => {
    const { dir, filePath } = tempFile();
    try {
        const store = new UsageStore(baseOptions(filePath, { now: () => PEAK_TIME }));
        store.record("u1", "Alex", { inputTokens: 100, cachedInputTokens: 0, outputTokens: 10 });
        const second = store.record("u1", "Alex", { inputTokens: 200, cachedInputTokens: 0, outputTokens: 20 });
        assert.equal(second.today.requests, 2);
        assert.equal(second.today.inputTokens, 300);
        assert.equal(second.total.requests, 2);
        assert.equal(second.total.inputTokens, 300);
        store.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("day rollover: today resets when the date changes, total does not", () => {
    const { dir, filePath } = tempFile();
    try {
        let now = new Date(Date.UTC(2026, 8, 14, 12, 0));
        const store = new UsageStore(baseOptions(filePath, { now: () => now }));
        store.record("u1", "Alex", { inputTokens: 100, cachedInputTokens: 0, outputTokens: 10 });

        now = new Date(Date.UTC(2026, 8, 15, 1, 0));
        const result = store.record("u1", "Alex", { inputTokens: 50, cachedInputTokens: 0, outputTokens: 5 });

        assert.equal(result.today.date, "2026-09-15");
        assert.equal(result.today.requests, 1);
        assert.equal(result.today.inputTokens, 50);
        assert.equal(result.total.requests, 2);
        assert.equal(result.total.inputTokens, 150);
        store.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("checkAllowed: rolls over lazily and does not block a fresh day", () => {
    const { dir, filePath } = tempFile();
    try {
        let now = new Date(Date.UTC(2026, 8, 14, 12, 0));
        const store = new UsageStore(baseOptions(filePath, { now: () => now, envLimits: { cost: 0, tokens: 0, requests: 1 } }));
        store.record("u1", "Alex", { inputTokens: 10, cachedInputTokens: 0, outputTokens: 1 });
        assert.equal(store.checkAllowed("u1").ok, false);

        now = new Date(Date.UTC(2026, 8, 15, 12, 0));
        assert.equal(store.checkAllowed("u1").ok, true);
        store.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("limit precedence: player override > store default > env default", () => {
    const { dir, filePath } = tempFile();
    try {
        const store = new UsageStore(baseOptions(filePath, { now: () => PEAK_TIME, envLimits: { cost: 0, tokens: 0, requests: 40 } }));
        assert.equal(store.effectiveLimit("u1", "requests"), 40);

        store.setLimit(null, "requests", 20);
        assert.equal(store.effectiveLimit("u1", "requests"), 20);

        store.setLimit("u1", "requests", 5, "Alex");
        assert.equal(store.effectiveLimit("u1", "requests"), 5);

        store.resetLimits("u1");
        assert.equal(store.effectiveLimit("u1", "requests"), 20);

        store.resetLimits(null);
        assert.equal(store.effectiveLimit("u1", "requests"), 40);
        store.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("limit: 'off' means unlimited and checkAllowed never rejects on it", () => {
    const { dir, filePath } = tempFile();
    try {
        const store = new UsageStore(baseOptions(filePath, { now: () => PEAK_TIME, envLimits: { cost: 0, tokens: 0, requests: 1 } }));
        store.record("u1", "Alex", { inputTokens: 10, cachedInputTokens: 0, outputTokens: 1 });
        assert.equal(store.checkAllowed("u1").ok, false);

        store.setLimit("u1", "requests", "off");
        assert.equal(store.effectiveLimit("u1", "requests"), "off");
        assert.equal(store.checkAllowed("u1").ok, true);
        store.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("checkAllowed: reports the first exceeded limit by name", () => {
    const { dir, filePath } = tempFile();
    try {
        const store = new UsageStore(
            baseOptions(filePath, { now: () => PEAK_TIME, envLimits: { cost: 1, tokens: 500_000, requests: 40 } })
        );
        store.record("u1", "Alex", { inputTokens: 400_000, cachedInputTokens: 0, outputTokens: 200_000 });
        const allowed = store.checkAllowed("u1");
        assert.equal(allowed.ok, false);
        if (!allowed.ok) {
            assert.match(allowed.reason, /daily token limit \(500k\) reached/);
        }
        store.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("persistence: round-trips paused, defaults, limits and counters through a real file", () => {
    const { dir, filePath } = tempFile();
    try {
        const store1 = new UsageStore(baseOptions(filePath, { now: () => PEAK_TIME }));
        store1.record("u1", "Alex", { inputTokens: 1000, cachedInputTokens: 100, outputTokens: 50 });
        store1.setLimit("u1", "cost", 5);
        store1.setLimit(null, "tokens", 10_000);
        store1.setPaused(true);
        store1.close();

        assert.ok(existsSync(filePath));
        const raw = JSON.parse(readFileSync(filePath, "utf8"));
        assert.equal(raw.version, 1);

        const store2 = new UsageStore(baseOptions(filePath, { now: () => PEAK_TIME }));
        assert.equal(store2.isPaused(), true);
        assert.equal(store2.effectiveLimit("u1", "cost"), 5);
        assert.equal(store2.effectiveLimit("u2", "tokens"), 10_000);
        const summary = store2.summary("u1");
        assert.equal(summary.name, "Alex");
        assert.equal(summary.total.inputTokens, 1000);
        assert.equal(summary.total.cachedInputTokens, 100);
        assert.equal(summary.total.outputTokens, 50);
        store2.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("corrupt file: backed up and the store starts empty", () => {
    const { dir, filePath } = tempFile();
    try {
        writeFileSync(filePath, "{ this is not valid JSON", "utf8");
        const store = new UsageStore(baseOptions(filePath, { now: () => PEAK_TIME }));
        assert.equal(store.isPaused(), false);
        assert.equal(store.summaryAll().length, 0);

        const entries = readdirSync(dir);
        assert.ok(entries.some(f => f.startsWith("usage.json.corrupt-")), `expected a corrupt-backup file, got: ${entries.join(", ")}`);
        store.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("missing file: the store starts empty without error", () => {
    const { dir, filePath } = tempFile();
    try {
        const store = new UsageStore(baseOptions(filePath, { now: () => PEAK_TIME }));
        assert.equal(store.isPaused(), false);
        assert.equal(store.summaryAll().length, 0);
        store.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("findByName: case-insensitive, and prefers the most recently updated match", () => {
    const { dir, filePath } = tempFile();
    try {
        let now = new Date(Date.UTC(2026, 8, 14, 1, 0));
        const store = new UsageStore(baseOptions(filePath, { now: () => now }));
        store.record("u1", "Alex", { inputTokens: 1, cachedInputTokens: 0, outputTokens: 1 });

        now = new Date(Date.UTC(2026, 8, 14, 2, 0));
        store.record("u2", "alex", { inputTokens: 1, cachedInputTokens: 0, outputTokens: 1 });

        const found = store.findByName("ALEX");
        assert.equal(found?.uuid, "u2");
        assert.equal(found?.name, "alex");

        assert.equal(store.findByName("nobody"), undefined);
        store.close();
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test("fmtTokens: plain below 1000, k below 1M (trailing .0 dropped), M above", () => {
    assert.equal(fmtTokens(0), "0");
    assert.equal(fmtTokens(999), "999");
    assert.equal(fmtTokens(21_900), "21.9k");
    assert.equal(fmtTokens(500_000), "500k");
    assert.equal(fmtTokens(1_200_000), "1.2M");
});

test("fmtCost: 2 decimals normally, 4 when below 0.01, currency prefix", () => {
    assert.equal(fmtCost(0, "USD"), "$0.00");
    assert.equal(fmtCost(1, "USD"), "$1.00");
    assert.equal(fmtCost(0.04, "USD"), "$0.04");
    assert.equal(fmtCost(0.0061, "USD"), "$0.0061");
    assert.equal(fmtCost(0.04, "CNY"), "CNY 0.04");
});
