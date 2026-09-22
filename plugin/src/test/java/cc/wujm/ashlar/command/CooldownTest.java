// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.command;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link Cooldown} (step6a-prompt.md). Pure Java, no Bukkit;
 * time is driven by an {@link AtomicLong}-backed clock instead of sleeping.
 */
class CooldownTest {

    @Test
    void firstCallIsAcceptedImmediately() {
        AtomicLong clock = new AtomicLong(1_000L);
        Cooldown cooldown = new Cooldown(5_000L, clock::get);

        assertEquals(0L, cooldown.remainingMillis(UUID.randomUUID()));
    }

    @Test
    void secondCallWithinTheWindowIsRejectedWithTheRightRemainingTime() {
        AtomicLong clock = new AtomicLong(1_000L);
        Cooldown cooldown = new Cooldown(5_000L, clock::get);
        UUID player = UUID.randomUUID();

        cooldown.record(player);
        clock.addAndGet(2_000L); // 2s elapsed of the 5s window

        assertEquals(3_000L, cooldown.remainingMillis(player));
    }

    @Test
    void acceptedAgainAfterTheWindowElapses() {
        AtomicLong clock = new AtomicLong(1_000L);
        Cooldown cooldown = new Cooldown(5_000L, clock::get);
        UUID player = UUID.randomUUID();

        cooldown.record(player);
        clock.addAndGet(5_000L); // exactly at the window boundary

        assertEquals(0L, cooldown.remainingMillis(player));
    }

    @Test
    void setCooldownMillisAppliesImmediatelyToTheNextCheck() {
        // step8j-prompt.md: agent.cooldown-seconds is hot - /ashlar reload calls setCooldownMillis.
        AtomicLong clock = new AtomicLong(1_000L);
        Cooldown cooldown = new Cooldown(5_000L, clock::get);
        UUID player = UUID.randomUUID();

        cooldown.record(player);
        clock.addAndGet(2_000L); // 2s elapsed of the original 5s window
        assertEquals(3_000L, cooldown.remainingMillis(player));

        cooldown.setCooldownMillis(2_000L); // shorten the window below the already-elapsed time
        assertEquals(0L, cooldown.remainingMillis(player), "the shortened window must apply to the next check");
    }

    @Test
    void staleEntriesAreEvictedWhenRecordIsCalled() {
        AtomicLong clock = new AtomicLong(0L);
        // A cooldown window far longer than the 10-minute eviction sweep: if the
        // stale entry survives the sweep, remainingMillis() below would still
        // report time owed. If it was evicted, the player looks never-seen (0).
        Cooldown cooldown = new Cooldown(20 * 60 * 1000L, clock::get);
        UUID stalePlayer = UUID.randomUUID();
        cooldown.record(stalePlayer);

        clock.set(11 * 60 * 1000L); // past the 10-minute eviction window
        cooldown.record(UUID.randomUUID()); // triggers the sweep

        assertEquals(0L, cooldown.remainingMillis(stalePlayer));
    }
}
