// SPDX-License-Identifier: AGPL-3.0-or-later

import type { ChatMessage, ContentPart } from "./provider.js";

export interface History {
    /** Returns this player's remembered messages, oldest first, evicting the player if idle past the TTL. */
    get(uuid: string): ChatMessage[];
    /** Appends one exchange's messages (a full player request: user message through final assistant reply) and trims. */
    append(uuid: string, exchange: ChatMessage[]): void;
}

interface PlayerEntry {
    /** One array per remembered exchange, oldest first. */
    exchanges: ChatMessage[][];
    lastUsedAt: number;
}

/**
 * Per-player chat history, bounded to `turns` exchanges (docs/prompts/
 * step6b-prompt.md), with idle-time TTL eviction and image redaction so
 * history never carries more than the two most recent images (DeepSeek
 * charges <= 1024 tokens per image, and only accepts them in `user`
 * messages at all - stale ones are pure waste).
 */
export class HistoryStore implements History {
    private readonly turns: number;
    private readonly ttlMs: number;
    private readonly now: () => number;
    private readonly players = new Map<string, PlayerEntry>();

    constructor(opts: { turns: number; ttlMinutes: number; now?: () => number }) {
        this.turns = opts.turns;
        this.ttlMs = Math.max(0, opts.ttlMinutes) * 60_000;
        this.now = opts.now ?? Date.now;
    }

    get(uuid: string): ChatMessage[] {
        const entry = this.players.get(uuid);
        if (!entry) return [];
        if (this.now() - entry.lastUsedAt > this.ttlMs) {
            this.players.delete(uuid);
            return [];
        }
        entry.lastUsedAt = this.now();
        return entry.exchanges.flat();
    }

    append(uuid: string, exchange: ChatMessage[]): void {
        let entry = this.players.get(uuid);
        if (!entry || this.now() - entry.lastUsedAt > this.ttlMs) {
            entry = { exchanges: [], lastUsedAt: this.now() };
            this.players.set(uuid, entry);
        }
        entry.exchanges.push(exchange);
        while (entry.exchanges.length > this.turns) {
            entry.exchanges.shift();
        }
        entry.exchanges = redactOldImages(entry.exchanges);
        entry.lastUsedAt = this.now();
    }
}

export function createHistory(opts: { turns: number; ttlMinutes: number; now?: () => number }): History {
    return new HistoryStore(opts);
}

function isImagePart(part: ContentPart): boolean {
    return part.type === "image_url";
}

/** Keeps only the two most recent image_url parts across every stored exchange; earlier ones become "[image omitted]" text parts. */
function redactOldImages(exchanges: ChatMessage[][]): ChatMessage[][] {
    let totalImages = 0;
    for (const exchange of exchanges) {
        for (const m of exchange) {
            if (Array.isArray(m.content)) {
                for (const part of m.content) {
                    if (isImagePart(part)) totalImages++;
                }
            }
        }
    }
    let toDrop = Math.max(0, totalImages - 2);
    if (toDrop === 0) return exchanges;

    return exchanges.map(exchange =>
        exchange.map(m => {
            if (!Array.isArray(m.content)) return m;
            const newContent: ContentPart[] = m.content.map(part => {
                if (isImagePart(part) && toDrop > 0) {
                    toDrop--;
                    return { type: "text", text: "[image omitted]" };
                }
                return part;
            });
            return { ...m, content: newContent };
        })
    );
}
