// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

import { createAdminHandler, type CancelOutcome } from "./admin.js";
import { parsePeakHours } from "./pricing.js";
import { UsageStore } from "./usage.js";

function newStore(): { store: UsageStore; dir: string } {
    const dir = mkdtempSync(join(tmpdir(), "ashlar-admin-test-"));
    const store = new UsageStore({
        filePath: join(dir, "usage.json"),
        priceInput: 0.3,
        priceCachedInput: 0.006,
        priceOutput: 1.2,
        currency: "USD",
        envLimits: { cost: 0, tokens: 0, requests: 40 },
        peakSchedule: parsePeakHours("always"),
        offPeakMultiplier: 0.5,
        saveDebounceMs: 0
    });
    return { store, dir };
}

function fakeSend(): { send: (uuid: string, text: string, kind?: "progress" | "final") => Promise<void>; sent: Array<{ uuid: string; text: string; kind?: string }> } {
    const sent: Array<{ uuid: string; text: string; kind?: string }> = [];
    return {
        send: async (uuid, text, kind) => {
            sent.push({ uuid, text, kind });
        },
        sent
    };
}

const BY = { name: "Op", uuid: "op-uuid" };

test("admin usage: self (null target) reports the caller's own usage", async () => {
    const { store, dir } = newStore();
    try {
        store.record(BY.uuid, BY.name, { inputTokens: 1000, cachedInputTokens: 0, outputTokens: 100 });
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "usage", by: BY, target: null, args: [] });

        assert.equal(sent.length, 1);
        assert.equal(sent[0]!.uuid, BY.uuid);
        assert.match(sent[0]!.text, /Usage for Op:/);
        assert.match(sent[0]!.text, /today: 1 requests/);
        assert.equal(sent[0]!.kind, "final");
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin usage: a named player resolved by uuid", async () => {
    const { store, dir } = newStore();
    try {
        store.record("alex-uuid", "Alex", { inputTokens: 500, cachedInputTokens: 0, outputTokens: 50 });
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "usage", by: BY, target: { name: "Alex", uuid: "alex-uuid" }, args: [] });

        assert.match(sent[0]!.text, /Usage for Alex:/);
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin usage: an offline named player is resolved by last-seen name", async () => {
    const { store, dir } = newStore();
    try {
        store.record("alex-uuid", "Alex", { inputTokens: 500, cachedInputTokens: 0, outputTokens: 50 });
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "usage", by: BY, target: { name: "alex", uuid: null }, args: [] });

        assert.match(sent[0]!.text, /Usage for Alex:/);
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin usage: an unknown player tells the caller", async () => {
    const { store, dir } = newStore();
    try {
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "usage", by: BY, target: { name: "Nobody", uuid: null }, args: [] });

        assert.match(sent[0]!.text, /Unknown player "Nobody"/);
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin usage: 'all' lists every player sorted by today's cost", async () => {
    const { store, dir } = newStore();
    try {
        store.record("u1", "Cheap", { inputTokens: 100, cachedInputTokens: 0, outputTokens: 10 });
        store.record("u2", "Pricey", { inputTokens: 1_000_000, cachedInputTokens: 0, outputTokens: 1_000_000 });
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "usage", by: BY, target: { name: "all", uuid: null }, args: [] });

        const text = sent[0]!.text;
        const pricelyIndex = text.indexOf("Pricey");
        const cheapIndex = text.indexOf("Cheap");
        assert.ok(pricelyIndex >= 0 && cheapIndex >= 0 && pricelyIndex < cheapIndex, "Pricey (higher cost) must be listed first");
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin limit: sets a per-player override and replies with the new effective limits", async () => {
    const { store, dir } = newStore();
    try {
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "limit", by: BY, target: { name: "Alex", uuid: "alex-uuid" }, args: ["cost", "5"] });

        assert.equal(store.effectiveLimit("alex-uuid", "cost"), 5);
        assert.match(sent[0]!.text, /Set limits for Alex/);
        assert.match(sent[0]!.text, /\$5\.00\/day/);
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin limit: null target sets the server default", async () => {
    const { store, dir } = newStore();
    try {
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "limit", by: BY, target: null, args: ["tokens", "10000"] });

        assert.equal(store.effectiveLimits(null).tokens, 10_000);
        assert.match(sent[0]!.text, /Set limits for the server default/);
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin limit: 'off' sets unlimited", async () => {
    const { store, dir } = newStore();
    try {
        const { send } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "limit", by: BY, target: { name: "Alex", uuid: "alex-uuid" }, args: ["requests", "off"] });

        assert.equal(store.effectiveLimit("alex-uuid", "requests"), "off");
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin limit: reset removes the override", async () => {
    const { store, dir } = newStore();
    try {
        store.setLimit("alex-uuid", "cost", 5, "Alex");
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "limit", by: BY, target: { name: "Alex", uuid: "alex-uuid" }, args: ["reset"] });

        assert.equal(store.effectiveLimit("alex-uuid", "cost"), "off");
        assert.match(sent[0]!.text, /Reset limits for Alex/);
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin limit: a non-positive value is rejected with an error, not applied", async () => {
    const { store, dir } = newStore();
    try {
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "limit", by: BY, target: { name: "Alex", uuid: "alex-uuid" }, args: ["cost", "-1"] });

        assert.match(sent[0]!.text, /must be a positive number/);
        assert.equal(store.effectiveLimit("alex-uuid", "cost"), "off");
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin limit: an unknown kind is rejected", async () => {
    const { store, dir } = newStore();
    try {
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "limit", by: BY, target: { name: "Alex", uuid: "alex-uuid" }, args: ["bogus", "5"] });

        assert.match(sent[0]!.text, /Unknown limit kind/);
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin cancel: running/queued reports success and notifies the target; none reports failure", async () => {
    const { store, dir } = newStore();
    try {
        const outcomes: Record<string, CancelOutcome> = { "alex-uuid": "running", "steve-uuid": "none" };
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: uuid => outcomes[uuid] ?? "none", send, currency: "USD" });

        await handler({ action: "cancel", by: BY, target: { name: "Alex", uuid: "alex-uuid" }, args: [] });
        assert.match(sent[0]!.text, /Cancelled Alex's request\./);
        assert.equal(sent[1]!.uuid, "alex-uuid");
        assert.match(sent[1]!.text, /cancelled by Op/);

        sent.length = 0;
        await handler({ action: "cancel", by: BY, target: { name: "Steve", uuid: "steve-uuid" }, args: [] });
        assert.match(sent[0]!.text, /Steve has no request running\./);
        assert.equal(sent.length, 1, "no notification is sent when there was nothing to cancel");
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin pause/resume: sets the store's pause flag and replies", async () => {
    const { store, dir } = newStore();
    try {
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "pause", by: BY, target: null, args: [] });
        assert.equal(store.isPaused(), true);
        assert.match(sent[0]!.text, /paused/i);

        await handler({ action: "resume", by: BY, target: null, args: [] });
        assert.equal(store.isPaused(), false);
        assert.match(sent[1]!.text, /resumed/i);
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});

test("admin: an unknown action is ignored without sending anything", async () => {
    const { store, dir } = newStore();
    try {
        const { send, sent } = fakeSend();
        const handler = createAdminHandler({ store, cancel: () => "none", send, currency: "USD" });

        await handler({ action: "teleport", by: BY, target: null, args: [] });

        assert.equal(sent.length, 0);
    } finally {
        store.close();
        rmSync(dir, { recursive: true, force: true });
    }
});
