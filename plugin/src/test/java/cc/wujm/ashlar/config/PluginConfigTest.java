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
        assertEquals(PluginConfig.AgentConfig.Mode.OFF, PluginConfig.AgentConfig.Mode.parse("false", LOGGER));
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
    void deploymentModeParsesTheFourValuesAndRejectsTheRest() throws ConfigException {
        assertEquals(PluginConfig.DeploymentMode.BOTH, PluginConfig.DeploymentMode.parse("both"));
        assertEquals(PluginConfig.DeploymentMode.MCP, PluginConfig.DeploymentMode.parse(" MCP "));
        assertEquals(PluginConfig.DeploymentMode.INGAME, PluginConfig.DeploymentMode.parse("ingame"));
        assertEquals(PluginConfig.DeploymentMode.EXTERNAL, PluginConfig.DeploymentMode.parse("external"));
        assertThrows(ConfigException.class, () -> PluginConfig.DeploymentMode.parse("embedded"));
        assertThrows(ConfigException.class, () -> PluginConfig.DeploymentMode.parse("off"));
        assertThrows(ConfigException.class, () -> PluginConfig.DeploymentMode.parse(""));
        assertThrows(ConfigException.class, () -> PluginConfig.DeploymentMode.parse(null));
    }

    @Test
    void deploymentModeDecidesServerAndAgent() {
        assertEquals(true, PluginConfig.DeploymentMode.BOTH.serverEnabled());
        assertEquals(PluginConfig.AgentConfig.Mode.EMBEDDED, PluginConfig.DeploymentMode.BOTH.agentMode());
        assertEquals(true, PluginConfig.DeploymentMode.MCP.serverEnabled());
        assertEquals(PluginConfig.AgentConfig.Mode.OFF, PluginConfig.DeploymentMode.MCP.agentMode());
        assertEquals(false, PluginConfig.DeploymentMode.INGAME.serverEnabled());
        assertEquals(PluginConfig.AgentConfig.Mode.EMBEDDED, PluginConfig.DeploymentMode.INGAME.agentMode());
        assertEquals(true, PluginConfig.DeploymentMode.EXTERNAL.serverEnabled());
        assertEquals(PluginConfig.AgentConfig.Mode.EXTERNAL, PluginConfig.DeploymentMode.EXTERNAL.agentMode());
    }

    @Test
    void legacyAgentModeMapsOntoDeploymentMode() {
        assertEquals(PluginConfig.DeploymentMode.BOTH,
                PluginConfig.DeploymentMode.fromLegacy(PluginConfig.AgentConfig.Mode.EMBEDDED, true));
        assertEquals(PluginConfig.DeploymentMode.INGAME,
                PluginConfig.DeploymentMode.fromLegacy(PluginConfig.AgentConfig.Mode.EMBEDDED, false));
        assertEquals(PluginConfig.DeploymentMode.MCP,
                PluginConfig.DeploymentMode.fromLegacy(PluginConfig.AgentConfig.Mode.OFF, true));
        assertEquals(PluginConfig.DeploymentMode.EXTERNAL,
                PluginConfig.DeploymentMode.fromLegacy(PluginConfig.AgentConfig.Mode.EXTERNAL, true));
    }

    // ---- engine.text-font-file (step8n-prompt.md &sect;A/C) --------------------------------
    // PluginConfig.load itself reads "engine.text-font-file" as a plain string (fc.getString(...,
    // "")) with no existence/format check of its own - that validation (does the path exist, is
    // it a directory, does Font.createFont accept it) happens off this path, in
    // cc.wujm.ashlar.engine.text.FontSource (exercised in FontSourceTest), so a bad path can never
    // make PluginConfig.load throw. These tests document that contract at the EngineConfig level,
    // the same way PluginConfig.load itself cannot be exercised directly here (FileConfiguration
    // needs paper-api, which is compileOnly and not on the test classpath).

    @Test
    void engineTextFontFileDefaultsToEmpty() {
        assertEquals("", new PluginConfig.EngineConfig(true, true, "").textFontFile());
    }

    @Test
    void engineTextFontFileNeverThrowsForAnUnreadablePath() {
        String bogus = "/no/such/path/definitely-missing.ttf";
        assertEquals(bogus, new PluginConfig.EngineConfig(true, true, bogus).textFontFile());
    }
}
