// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { test } from "node:test";

import { HistoryStore } from "./history.js";
import type { ChatMessage, ContentPart } from "./provider.js";

function userMsg(text: string): ChatMessage {
    return { role: "user", content: text };
}

function assistantMsg(text: string): ChatMessage {
    return { role: "assistant", content: text };
}

test("HistoryStore: trims to the configured number of exchanges", () => {
    const history = new HistoryStore({ turns: 3, ttlMinutes: 30 });
    for (let i = 1; i <= 5; i++) {
        history.append("p1", [userMsg(`request ${i}`), assistantMsg(`reply ${i}`)]);
    }
    const messages = history.get("p1");
    assert.equal(messages.length, 6); // the 3 most recent exchanges, 2 messages each
    assert.equal(messages[0]!.content, "request 3");
    assert.equal(messages[1]!.content, "reply 3");
    assert.equal(messages[messages.length - 2]!.content, "request 5");
    assert.equal(messages[messages.length - 1]!.content, "reply 5");
});

test("HistoryStore: an unset player has no history", () => {
    const history = new HistoryStore({ turns: 3, ttlMinutes: 30 });
    assert.deepEqual(history.get("nobody"), []);
});

test("HistoryStore: TTL evicts an idle player's history on the next get()", () => {
    let now = 1_000_000;
    const history = new HistoryStore({ turns: 5, ttlMinutes: 10, now: () => now });
    history.append("p1", [userMsg("hi")]);
    assert.equal(history.get("p1").length, 1);

    now += 11 * 60_000; // past the 10-minute TTL
    assert.deepEqual(history.get("p1"), []);
});

test("HistoryStore: appending after TTL eviction starts a fresh history rather than appending to the stale one", () => {
    let now = 0;
    const history = new HistoryStore({ turns: 5, ttlMinutes: 1, now: () => now });
    history.append("p1", [userMsg("first")]);
    now += 2 * 60_000; // past the 1-minute TTL
    history.append("p1", [userMsg("second")]);

    const messages = history.get("p1");
    assert.equal(messages.length, 1);
    assert.equal(messages[0]!.content, "second");
});

test("HistoryStore: get() refreshes lastUsedAt so an active player is not evicted", () => {
    let now = 0;
    const history = new HistoryStore({ turns: 5, ttlMinutes: 10, now: () => now });
    history.append("p1", [userMsg("hi")]);

    now += 9 * 60_000; // still within TTL
    assert.equal(history.get("p1").length, 1);

    now += 9 * 60_000; // another 9 minutes: 18 total since append, but only 9 since the refreshing get()
    assert.equal(history.get("p1").length, 1);
});

function imageMessage(tag: string): ChatMessage {
    return {
        role: "user",
        content: [
            { type: "text", text: `image set ${tag}` },
            { type: "image_url", image_url: { url: `data:image/png;base64,${tag}` } }
        ]
    };
}

function imageParts(messages: ChatMessage[]): ContentPart[] {
    return messages.flatMap(m => (Array.isArray(m.content) ? m.content.filter(p => p.type === "image_url") : []));
}

test("HistoryStore: image redaction keeps exactly the two newest images", () => {
    const history = new HistoryStore({ turns: 10, ttlMinutes: 30 });
    for (const tag of ["img1", "img2", "img3", "img4"]) {
        history.append("p1", [imageMessage(tag)]);
    }

    const messages = history.get("p1");
    const images = imageParts(messages) as Array<{ type: "image_url"; image_url: { url: string } }>;
    assert.equal(images.length, 2);
    assert.equal(images[0]!.image_url.url, "data:image/png;base64,img3");
    assert.equal(images[1]!.image_url.url, "data:image/png;base64,img4");

    const omittedCount = messages.filter(
        m => Array.isArray(m.content) && m.content.some(p => p.type === "text" && p.text === "[image omitted]")
    ).length;
    assert.equal(omittedCount, 2);
});

test("HistoryStore: image redaction re-runs on every append, so trimming an exchange with images out doesn't leave fewer than two", () => {
    const history = new HistoryStore({ turns: 2, ttlMinutes: 30 });
    history.append("p1", [imageMessage("img1")]);
    history.append("p1", [imageMessage("img2")]);
    history.append("p1", [imageMessage("img3")]); // exchange 1 (img1) is trimmed out here

    const images = imageParts(history.get("p1")) as Array<{ type: "image_url"; image_url: { url: string } }>;
    assert.equal(images.length, 2);
    assert.equal(images[0]!.image_url.url, "data:image/png;base64,img2");
    assert.equal(images[1]!.image_url.url, "data:image/png;base64,img3");
});
