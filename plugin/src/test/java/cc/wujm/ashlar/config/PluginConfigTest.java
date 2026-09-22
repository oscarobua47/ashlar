// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.config;

import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@code agent.*} config parsing (docs/private/prompts/step8b-prompt.md &sect;1).
 * {@link PluginConfig#load} itself takes a Bukkit {@code FileConfiguration}, which is not on the
 * test classpath (paper-api is {@code compileOnly}), so these exercise the pure validation
 * helpers directly, as the prompt allows.
 */
class PluginConfigTest {

    private static final Logger LOGGER = Logger.getLogger("PluginConfigTest");

    @Test
    void modeParsesEachValueCaseInsensitively() {
        assertEquals(PluginConfig.AgentConfig.Mode.EMBEDDED, PluginConfig.AgentConfig.Mode.parse("embedded", LOGGER));
        assertEquals(PluginConfig.AgentConfig.Mode.EMBEDDED, PluginConfig.AgentConfig.Mode.parse("EMBEDDED", LOGGER));
        assertEquals(PluginConfig.AgentConfig.Mode.EXTERNAL, PluginConfig.AgentConfig.Mode.parse("external", LOGGER));
        assertEquals(PluginConfig.AgentConfig.Mode.OFF, PluginConfig.AgentConfig.Mode.parse("off", LOGGER));
        assertEquals(PluginConfig.AgentConfig.Mode.OFF, PluginConfig.AgentConfig.Mode.parse(" Off ", LOGGER));
    }

    @Test
    void modeFallsBackToEmbeddedOnInvalidOrMissingValue() {
        assertEquals(PluginConfig.AgentConfig.Mode.EMBEDDED, PluginConfig.AgentConfig.Mode.parse("bogus", LOGGER));
        assertEquals(PluginConfig.AgentConfig.Mode.EMBEDDED, PluginConfig.AgentConfig.Mode.parse(null, LOGGER));
        assertEquals(PluginConfig.AgentConfig.Mode.EMBEDDED, PluginConfig.AgentConfig.Mode.parse("", LOGGER));
    }

    @Test
    void positiveOrDefaultValueKeepsAPositiveNumber() {
        assertEquals(42, PluginConfig.positiveOrDefaultValue(42, 25, "agent.model.max-tool-calls", LOGGER));
    }

    @Test
    void positiveOrDefaultValueFallsBackOnZeroOrNegative() {
        assertEquals(25, PluginConfig.positiveOrDefaultValue(0, 25, "agent.model.max-tool-calls", LOGGER));
        assertEquals(25, PluginConfig.positiveOrDefaultValue(-5, 25, "agent.model.max-tool-calls", LOGGER));
    }

    @Test
    void nonNegativeOrDefaultValueAllowsZero() {
        assertEquals(0, PluginConfig.nonNegativeOrDefaultValue(0, 40, "agent.limits.max-requests-per-player-per-day", LOGGER));
        assertEquals(7, PluginConfig.nonNegativeOrDefaultValue(7, 40, "agent.limits.max-requests-per-player-per-day", LOGGER));
    }

    @Test
    void nonNegativeOrDefaultValueFallsBackOnNegative() {
        assertEquals(40, PluginConfig.nonNegativeOrDefaultValue(-1, 40, "agent.limits.max-requests-per-player-per-day", LOGGER));
    }

    @Test
    void nonNegativeDoubleOrDefaultValueAllowsZeroAndFractional() {
        assertEquals(0.0, PluginConfig.nonNegativeDoubleOrDefaultValue(0, 0.3, "agent.pricing.input", LOGGER));
        assertEquals(0.006, PluginConfig.nonNegativeDoubleOrDefaultValue(0.006, 0.3, "agent.pricing.input", LOGGER));
    }

    @Test
    void nonNegativeDoubleOrDefaultValueFallsBackOnNegativeOrNonFinite() {
        assertEquals(0.3, PluginConfig.nonNegativeDoubleOrDefaultValue(-0.1, 0.3, "agent.pricing.input", LOGGER));
        assertEquals(0.3, PluginConfig.nonNegativeDoubleOrDefaultValue(Double.NaN, 0.3, "agent.pricing.input", LOGGER));
    }

    @Test
    void validateImageDetailAcceptsTheThreeKnownValues() {
        assertEquals("low", PluginConfig.validateImageDetail("low", LOGGER));
        assertEquals("high", PluginConfig.validateImageDetail("high", LOGGER));
        assertEquals("auto", PluginConfig.validateImageDetail("auto", LOGGER));
    }

    @Test
    void validateImageDetailFallsBackToHighOnAnythingElse() {
        assertEquals("high", PluginConfig.validateImageDetail("ultra", LOGGER));
        assertEquals("high", PluginConfig.validateImageDetail("", LOGGER));
    }

    @Test
    void validatePeakHoursKeepsAValidSchedule() {
        assertEquals("always", PluginConfig.validatePeakHours("always", LOGGER));
        assertEquals("mon-fri 01:00-04:00,06:00-10:00",
                PluginConfig.validatePeakHours("mon-fri 01:00-04:00,06:00-10:00", LOGGER));
    }

    @Test
    void validatePeakHoursFallsBackToTheDefaultOnAParseFailure() {
        assertEquals(PluginConfig.DEFAULT_PEAK_HOURS, PluginConfig.validatePeakHours("not a schedule", LOGGER));
        assertEquals(PluginConfig.DEFAULT_PEAK_HOURS, PluginConfig.validatePeakHours("mon-fri 25:00-26:00", LOGGER));
    }

    // ---- language (step8i-prompt.md) ----

    @Test
    void validateLanguageAcceptsTheThreeKnownValues() throws ConfigException {
        assertEquals("en", PluginConfig.validateLanguage("en"));
        assertEquals("zh_CN", PluginConfig.validateLanguage("zh_CN"));
        assertEquals("auto", PluginConfig.validateLanguage("auto"));
    }

    @Test
    void validateLanguageRejectsAnythingElse() {
        assertThrows(ConfigException.class, () -> PluginConfig.validateLanguage("English"));
        assertThrows(ConfigException.class, () -> PluginConfig.validateLanguage("zh"));
        assertThrows(ConfigException.class, () -> PluginConfig.validateLanguage(""));
        assertThrows(ConfigException.class, () -> PluginConfig.validateLanguage(null));
    }

    @Test
    void tokenIsRequiredOnlyWhileTheServerIsEnabled() throws ConfigException {
        assertEquals("0123456789abcdef", PluginConfig.validateToken(true, "0123456789abcdef"));
        assertThrows(ConfigException.class, () -> PluginConfig.validateToken(true, ""));
        assertThrows(ConfigException.class, () -> PluginConfig.validateToken(true, null));
        assertThrows(ConfigException.class, () -> PluginConfig.validateToken(true, "short"));
        assertEquals("", PluginConfig.validateToken(false, ""));
        assertEquals("", PluginConfig.validateToken(false, null));
        assertEquals("short", PluginConfig.validateToken(false, "short"));
    }

    @Test
    void externalModeNeedsTheServer() throws ConfigException {
        PluginConfig.validateModeAgainstServer(true, PluginConfig.AgentConfig.Mode.EXTERNAL);
        PluginConfig.validateModeAgainstServer(false, PluginConfig.AgentConfig.Mode.EMBEDDED);
        PluginConfig.validateModeAgainstServer(false, PluginConfig.AgentConfig.Mode.OFF);
        assertThrows(ConfigException.class,
                () -> PluginConfig.validateModeAgainstServer(false, PluginConfig.AgentConfig.Mode.EXTERNAL));
    }
}
