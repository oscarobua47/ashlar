// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link AshlarArgs} (step6e-prompt.md): every grammar line
 * plus the invalid cases named in the prompt. Pure Java, no Bukkit.
 */
class AshlarArgsTest {

    private static String[] words(String line) {
        return line.split(" ");
    }

    @Test
    void plainRequestIsRequest() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("build a house"));
        assertEquals(AshlarArgs.Kind.REQUEST, parsed.kind());
    }

    @Test
    void emptyArgsIsInvalidWithTopUsage() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(new String[0]);
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
        assertEquals(AshlarArgs.USAGE_TOP, parsed.error());
    }

    @Test
    void cancelAloneIsCancelSelf() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("cancel"));
        assertEquals(AshlarArgs.Kind.CANCEL_SELF, parsed.kind());
        assertNull(parsed.targetName());
    }

    @Test
    void cancelWithPlayerIsCancelOther() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("cancel Alex"));
        assertEquals(AshlarArgs.Kind.CANCEL_OTHER, parsed.kind());
        assertEquals("Alex", parsed.targetName());
    }

    @Test
    void cancelIsCaseInsensitive() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("CaNcEl"));
        assertEquals(AshlarArgs.Kind.CANCEL_SELF, parsed.kind());
    }

    @Test
    void cancelWithTwoArgsIsInvalid() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("cancel a b"));
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
        assertNotNull(parsed.error());
    }

    @Test
    void usageAloneIsUsageSelf() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("usage"));
        assertEquals(AshlarArgs.Kind.USAGE_SELF, parsed.kind());
    }

    @Test
    void usageWithPlayerIsUsageOther() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("usage Alex"));
        assertEquals(AshlarArgs.Kind.USAGE_OTHER, parsed.kind());
        assertEquals("Alex", parsed.targetName());
    }

    @Test
    void usageAllIsUsageAll() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("usage all"));
        assertEquals(AshlarArgs.Kind.USAGE_ALL, parsed.kind());
    }

    @Test
    void usageAllIsCaseInsensitive() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("usage ALL"));
        assertEquals(AshlarArgs.Kind.USAGE_ALL, parsed.kind());
    }

    @Test
    void usageWithTwoExtraArgsIsInvalid() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("usage a b"));
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
        assertNotNull(parsed.error());
    }

    @Test
    void limitSetsCostForPlayer() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("limit Alex cost 5"));
        assertEquals(AshlarArgs.Kind.LIMIT, parsed.kind());
        assertEquals("Alex", parsed.targetName());
        assertEquals(List.of("cost", "5"), parsed.args());
    }

    @Test
    void limitSetsTokensOff() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("limit Alex tokens off"));
        assertEquals(AshlarArgs.Kind.LIMIT, parsed.kind());
        assertEquals(List.of("tokens", "off"), parsed.args());
    }

    @Test
    void limitSetsRequestsForDefault() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("limit default requests 100"));
        assertEquals(AshlarArgs.Kind.LIMIT, parsed.kind());
        assertEquals("default", parsed.targetName());
        assertEquals(List.of("requests", "100"), parsed.args());
    }

    @Test
    void limitResetForPlayer() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("limit Alex reset"));
        assertEquals(AshlarArgs.Kind.LIMIT, parsed.kind());
        assertEquals("Alex", parsed.targetName());
        assertEquals(List.of("reset"), parsed.args());
    }

    @Test
    void limitResetForDefault() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("limit default reset"));
        assertEquals(AshlarArgs.Kind.LIMIT, parsed.kind());
        assertEquals("default", parsed.targetName());
        assertEquals(List.of("reset"), parsed.args());
    }

    @Test
    void limitWithBadKindIsInvalid() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("limit Alex speed 5"));
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
        assertNotNull(parsed.error());
    }

    @Test
    void limitWithNonNumericValueIsInvalid() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("limit Alex cost abc"));
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
        assertNotNull(parsed.error());
    }

    @Test
    void limitWithNegativeValueIsInvalid() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("limit Alex cost -5"));
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
    }

    @Test
    void limitAloneIsInvalid() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("limit"));
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
        assertNotNull(parsed.error());
    }

    @Test
    void pauseAloneIsPause() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("pause"));
        assertEquals(AshlarArgs.Kind.PAUSE, parsed.kind());
    }

    @Test
    void pauseWithExtraArgsIsInvalid() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("pause now"));
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
    }

    @Test
    void resumeAloneIsResume() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("resume"));
        assertEquals(AshlarArgs.Kind.RESUME, parsed.kind());
    }

    @Test
    void resumeWithExtraArgsIsInvalid() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("resume now"));
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
    }

    @Test
    void allowWithPlayerIsAllow() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("allow Alex"));
        assertEquals(AshlarArgs.Kind.ALLOW, parsed.kind());
        assertEquals("Alex", parsed.targetName());
    }

    @Test
    void allowWithoutPlayerIsInvalid() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("allow"));
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
    }

    @Test
    void denyWithPlayerIsDeny() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("deny Alex"));
        assertEquals(AshlarArgs.Kind.DENY, parsed.kind());
        assertEquals("Alex", parsed.targetName());
    }

    @Test
    void denyWithoutPlayerIsInvalid() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("deny"));
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
    }

    @Test
    void allowedAloneIsAllowed() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("allowed"));
        assertEquals(AshlarArgs.Kind.ALLOWED, parsed.kind());
    }

    @Test
    void allowedWithExtraArgsIsInvalid() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("allowed extra"));
        assertEquals(AshlarArgs.Kind.INVALID, parsed.kind());
    }

    @Test
    void helpAloneIsHelp() {
        AshlarArgs.Parsed parsed = AshlarArgs.parse(words("help"));
        assertEquals(AshlarArgs.Kind.HELP, parsed.kind());
    }

    @Test
    void askPrefixIsARequestWithoutTheKeyword() {
        AshlarArgs.Parsed p = AshlarArgs.parse(new String[]{"ask", "usage", "of", "stone"});
        assertEquals(AshlarArgs.Kind.REQUEST, p.kind());
        assertEquals(List.of("usage", "of", "stone"), p.args());
    }

    @Test
    void askWithoutTextIsInvalid() {
        AshlarArgs.Parsed p = AshlarArgs.parse(new String[]{"ask"});
        assertEquals(AshlarArgs.Kind.INVALID, p.kind());
        assertEquals(AshlarArgs.USAGE_ASK, p.error());
    }

    @Test
    void simulateFromConsoleWithFacingParsesCoordinatesFacingAndText() {
        AshlarArgs.Parsed p = AshlarArgs.parse(words("simulate 0 70 0 south build a small stone hut"), true);
        assertEquals(AshlarArgs.Kind.SIMULATE, p.kind());
        AshlarArgs.Simulate sim = p.simulate();
        assertEquals(0, sim.x());
        assertEquals(70, sim.y());
        assertEquals(0, sim.z());
        assertEquals("south", sim.facing());
        assertEquals(List.of("build", "a", "small", "stone", "hut"), sim.text());
    }

    @Test
    void simulateWithoutFacingDefaultsToSouth() {
        AshlarArgs.Parsed p = AshlarArgs.parse(words("simulate 1 2 3 restore the snapshot"), true);
        assertEquals(AshlarArgs.Kind.SIMULATE, p.kind());
        AshlarArgs.Simulate sim = p.simulate();
        assertEquals(1, sim.x());
        assertEquals(2, sim.y());
        assertEquals(3, sim.z());
        assertEquals("south", sim.facing());
        assertEquals(List.of("restore", "the", "snapshot"), sim.text());
    }

    @Test
    void simulateFacingIsCaseInsensitive() {
        AshlarArgs.Parsed p = AshlarArgs.parse(words("simulate 0 70 0 NORTH look around"), true);
        assertEquals(AshlarArgs.Kind.SIMULATE, p.kind());
        assertEquals("north", p.simulate().facing());
        assertEquals(List.of("look", "around"), p.simulate().text());
    }

    @Test
    void simulateFromAPlayerIsInvalidWithUsageLine() {
        AshlarArgs.Parsed p = AshlarArgs.parse(words("simulate 0 70 0 south build a hut"), false);
        assertEquals(AshlarArgs.Kind.INVALID, p.kind());
        assertEquals(AshlarArgs.USAGE_SIMULATE, p.error());
    }

    @Test
    void simulateWithNonNumericCoordinatesIsInvalid() {
        AshlarArgs.Parsed p = AshlarArgs.parse(words("simulate a b c build a hut"), true);
        assertEquals(AshlarArgs.Kind.INVALID, p.kind());
    }

    @Test
    void simulateWithoutTextIsInvalid() {
        AshlarArgs.Parsed p = AshlarArgs.parse(words("simulate 0 70 0 south"), true);
        assertEquals(AshlarArgs.Kind.INVALID, p.kind());
    }

    @Test
    void simulateWithTooFewArgsIsInvalid() {
        AshlarArgs.Parsed p = AshlarArgs.parse(words("simulate 0 70 0"), true);
        assertEquals(AshlarArgs.Kind.INVALID, p.kind());
    }

    @Test
    void defaultSingleArgParseIsEquivalentToNonConsole() {
        AshlarArgs.Parsed p = AshlarArgs.parse(words("simulate 0 70 0 south build a hut"));
        assertEquals(AshlarArgs.Kind.INVALID, p.kind());
        assertEquals(AshlarArgs.USAGE_SIMULATE, p.error());
    }

    @Test
    void resetIsItsOwnKind() {
        assertEquals(AshlarArgs.Kind.RESET, AshlarArgs.parse(new String[]{"reset"}).kind());
        assertEquals(AshlarArgs.Kind.INVALID, AshlarArgs.parse(new String[]{"reset", "now"}).kind());
    }
}
