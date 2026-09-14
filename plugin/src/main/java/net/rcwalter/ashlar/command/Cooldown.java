// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.command;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-player rate limit for {@code /ashlar} (step6a-prompt.md). Pure Java,
 * no Bukkit: the clock is injected as a {@link LongSupplier} so tests can
 * control time without sleeping.
 */
public final class Cooldown {

    // Evict an entry whenever record() is called and it is older than this,
    // regardless of the configured cooldown window (spec: "simple sweep;
    // the map is tiny").
    private static final long EVICT_AFTER_MILLIS = 10 * 60 * 1000L;

    private final Map<UUID, Long> lastAcceptedMillis = new ConcurrentHashMap<>();
    private final long cooldownMillis;
    private final LongSupplier clock;

    public Cooldown(long cooldownMillis, LongSupplier clock) {
        this.cooldownMillis = cooldownMillis;
        this.clock = clock;
    }

    /** Milliseconds until {@code player} may be accepted again; 0 if a request would be accepted right now. */
    public long remainingMillis(UUID player) {
        Long last = lastAcceptedMillis.get(player);
        if (last == null) {
            return 0L;
        }
        long elapsed = clock.getAsLong() - last;
        return Math.max(0L, cooldownMillis - elapsed);
    }

    /** Records that {@code player} was just accepted, and sweeps entries older than 10 minutes. */
    public void record(UUID player) {
        long now = clock.getAsLong();
        lastAcceptedMillis.put(player, now);
        lastAcceptedMillis.entrySet().removeIf(entry -> now - entry.getValue() > EVICT_AFTER_MILLIS);
    }
}
