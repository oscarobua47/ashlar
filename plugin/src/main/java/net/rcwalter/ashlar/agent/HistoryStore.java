// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import net.rcwalter.ashlar.agent.model.ChatMessage;
import net.rcwalter.ashlar.agent.model.ContentPart;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-player chat history, bounded to {@code turns} exchanges, with idle-time TTL eviction and
 * image redaction so history never carries more than the two most recent images (pure-Java port
 * of {@code mcp-server/src/agent/history.ts}'s {@code HistoryStore}: DeepSeek charges <= 1024
 * tokens per image, and only accepts them in {@code user} messages at all - stale ones are pure
 * waste).
 */
public final class HistoryStore implements History {

    private static final class PlayerEntry {
        final List<List<ChatMessage>> exchanges = new ArrayList<>();
        long lastUsedAtMs;
    }

    private final int turns;
    private final long ttlMs;
    private final Supplier<Instant> now;
    private final Map<String, PlayerEntry> players = new HashMap<>();

    public HistoryStore(int turns, long ttlMinutes) {
        this(turns, ttlMinutes, Instant::now);
    }

    public HistoryStore(int turns, long ttlMinutes, Supplier<Instant> now) {
        this.turns = turns;
        this.ttlMs = Math.max(0, ttlMinutes) * 60_000L;
        this.now = now != null ? now : Instant::now;
    }

    @Override
    public synchronized List<ChatMessage> get(String uuid) {
        PlayerEntry entry = players.get(uuid);
        if (entry == null) {
            return List.of();
        }
        long nowMs = now.get().toEpochMilli();
        if (nowMs - entry.lastUsedAtMs > ttlMs) {
            players.remove(uuid);
            return List.of();
        }
        entry.lastUsedAtMs = nowMs;
        List<ChatMessage> flat = new ArrayList<>();
        for (List<ChatMessage> exchange : entry.exchanges) {
            flat.addAll(exchange);
        }
        return flat;
    }

    @Override
    public synchronized boolean clear(String uuid) {
        return players.remove(uuid) != null;
    }

    @Override
    public synchronized void append(String uuid, List<ChatMessage> exchange) {
        long nowMs = now.get().toEpochMilli();
        PlayerEntry entry = players.get(uuid);
        if (entry == null || nowMs - entry.lastUsedAtMs > ttlMs) {
            entry = new PlayerEntry();
            players.put(uuid, entry);
        }
        entry.exchanges.add(new ArrayList<>(exchange));
        while (entry.exchanges.size() > turns) {
            entry.exchanges.remove(0);
        }
        redactOldImages(entry.exchanges);
        entry.lastUsedAtMs = nowMs;
    }

    /** Keeps only the two most recent image_url parts across every stored exchange; earlier ones become "[image omitted]" text parts. */
    private static void redactOldImages(List<List<ChatMessage>> exchanges) {
        int totalImages = 0;
        for (List<ChatMessage> exchange : exchanges) {
            for (ChatMessage m : exchange) {
                if (m.contentParts() != null) {
                    for (ContentPart part : m.contentParts()) {
                        if (part.isImage()) {
                            totalImages++;
                        }
                    }
                }
            }
        }
        int toDrop = Math.max(0, totalImages - 2);
        if (toDrop == 0) {
            return;
        }

        for (List<ChatMessage> exchange : exchanges) {
            for (int i = 0; i < exchange.size(); i++) {
                ChatMessage m = exchange.get(i);
                if (m.contentParts() == null) {
                    continue;
                }
                List<ContentPart> newParts = new ArrayList<>();
                boolean changed = false;
                for (ContentPart part : m.contentParts()) {
                    if (part.isImage() && toDrop > 0) {
                        toDrop--;
                        newParts.add(ContentPart.text("[image omitted]"));
                        changed = true;
                    } else {
                        newParts.add(part);
                    }
                }
                if (changed) {
                    exchange.set(i, m.withContentParts(newParts));
                }
            }
        }
    }
}
