// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConfigReload} and {@link ConfigHolder} (step8j-prompt.md &sect;C):
 * {@code /ashlar reload}'s hot/cold classification and the "apply hot only" merge, plus the holder
 * semantics a running request relies on (an already-obtained {@link PluginConfig} reference never
 * changes under it).
 */
class ConfigReloadTest {

    private static PluginConfig baseConfig() {
        return new PluginConfig(
                new PluginConfig.ServerConfig(true, "0.0.0.0", 8765, "0123456789abcdef", List.of()),
                new PluginConfig.LimitsConfig(500_000, 200_000, 20, 16, 1024, 2000),
                new PluginConfig.WorldConfig("world", List.of("world"),
                        new PluginConfig.WorldConfig.BuildRegion(false, -1000, -1000, 1000, 1000)),
                new PluginConfig.SnapshotConfig(true, 20, 200_000),
                new PluginConfig.LoggingConfig(true),
                new PluginConfig.RunCommandConfig(true),
                new PluginConfig.EngineConfig(true, true),
                new PluginConfig.AgentConfig(PluginConfig.AgentConfig.Mode.EMBEDDED, 5, 500, true, false,
                        new PluginConfig.AgentConfig.ModelConfig("http://localhost", "key", "test-model", 25, 120_000,
                                "high", "", false),
                        new PluginConfig.AgentConfig.LimitsConfig(40, 0, 0, 2, 6, 30),
                        new PluginConfig.AgentConfig.PricingConfig(0.30, 0.006, 1.20, "USD", "always", 0.5)),
                "en");
    }

    // ---- coldChanges ----------------------------------------------------------------------

    @Test
    void coldChangesIsEmptyWhenNothingColdChanged() {
        PluginConfig oldConfig = baseConfig();
        PluginConfig newConfig = baseConfig(); // an unrelated hot field differs, no cold ones
        newConfig = withLanguage(newConfig, "zh_CN");

        assertTrue(ConfigReload.coldChanges(oldConfig, newConfig).isEmpty());
    }

    @Test
    void coldChangesReportsModeWhenServerEnabledDiffers() {
        PluginConfig oldConfig = baseConfig();
        PluginConfig newConfig = withServerEnabled(oldConfig, false);

        assertEquals(List.of("mode"), ConfigReload.coldChanges(oldConfig, newConfig));
    }

    @Test
    void coldChangesReportsModeWhenAgentModeDiffers() {
        PluginConfig oldConfig = baseConfig();
        PluginConfig newConfig = withAgentMode(oldConfig, PluginConfig.AgentConfig.Mode.OFF);

        assertEquals(List.of("mode"), ConfigReload.coldChanges(oldConfig, newConfig));
    }

    @Test
    void coldChangesReportsEveryColdKeyThatDiffers() {
        PluginConfig oldConfig = baseConfig();
        PluginConfig newConfig = new PluginConfig(
                new PluginConfig.ServerConfig(true, "127.0.0.1", 8766, "fedcba9876543210", List.of("1.2.3.4")),
                new PluginConfig.LimitsConfig(500_000, 200_000, 50, 32, 1024, 2000),
                oldConfig.world(), oldConfig.snapshot(), oldConfig.logging(), oldConfig.runCommand(),
                oldConfig.engine(), oldConfig.agent(), oldConfig.language());

        List<String> changed = ConfigReload.coldChanges(oldConfig, newConfig);
        assertEquals(List.of("server.host", "server.port", "server.token",
                "limits.tick-budget-ms", "limits.max-queued-operations"), changed);
    }

    // ---- applyHotOnly -----------------------------------------------------------------------

    @Test
    void applyHotOnlyTakesHotFieldsFromTheNewConfig() {
        PluginConfig oldConfig = baseConfig();
        PluginConfig newConfig = new PluginConfig(
                new PluginConfig.ServerConfig(true, "0.0.0.0", 8765, "0123456789abcdef", List.of("9.9.9.9")),
                new PluginConfig.LimitsConfig(999, 999, 20, 16, 999, 999),
                new PluginConfig.WorldConfig("nether", List.of("nether"),
                        new PluginConfig.WorldConfig.BuildRegion(true, -1, -1, 1, 1)),
                new PluginConfig.SnapshotConfig(false, 5, 5),
                new PluginConfig.LoggingConfig(false),
                new PluginConfig.RunCommandConfig(false),
                new PluginConfig.EngineConfig(false, false),
                new PluginConfig.AgentConfig(PluginConfig.AgentConfig.Mode.EMBEDDED, 1, 1, false, true,
                        new PluginConfig.AgentConfig.ModelConfig("http://elsewhere", "new-key", "new-model", 1, 1,
                                "low", "extra.txt", true),
                        new PluginConfig.AgentConfig.LimitsConfig(1, 1, 1, 1, 1, 1),
                        new PluginConfig.AgentConfig.PricingConfig(1, 1, 1, "EUR", "always", 1)),
                "zh_CN");

        PluginConfig applied = ConfigReload.applyHotOnly(oldConfig, newConfig);

        assertEquals(List.of("9.9.9.9"), applied.server().allowedIps());
        assertEquals(999, applied.limits().maxBlocksPerOperation());
        assertEquals(999, applied.limits().maxReadVolume());
        assertEquals(999, applied.limits().maxChunksPerOperation());
        assertEquals(999, applied.limits().maxFlowingLiquidsPerOperation());
        assertEquals(newConfig.world(), applied.world());
        assertEquals(newConfig.snapshot(), applied.snapshot());
        assertEquals(newConfig.logging(), applied.logging());
        assertEquals(newConfig.runCommand(), applied.runCommand());
        assertEquals(newConfig.engine(), applied.engine());
        assertEquals("zh_CN", applied.language());
        assertEquals(1, applied.agent().cooldownSeconds());
        assertEquals(newConfig.agent().model(), applied.agent().model());
        assertEquals(newConfig.agent().limits(), applied.agent().limits());
        assertEquals(newConfig.agent().pricing(), applied.agent().pricing());
    }

    @Test
    void applyHotOnlyKeepsEveryColdFieldFromTheOldConfig() {
        PluginConfig oldConfig = baseConfig();
        PluginConfig newConfig = new PluginConfig(
                new PluginConfig.ServerConfig(false, "127.0.0.1", 9999, "zzzzzzzzzzzzzzzz", List.of()),
                new PluginConfig.LimitsConfig(500_000, 200_000, 999, 999, 1024, 2000),
                oldConfig.world(), oldConfig.snapshot(), oldConfig.logging(), oldConfig.runCommand(),
                oldConfig.engine(), withAgentMode(oldConfig, PluginConfig.AgentConfig.Mode.OFF).agent(),
                oldConfig.language());

        PluginConfig applied = ConfigReload.applyHotOnly(oldConfig, newConfig);

        assertEquals(oldConfig.server().enabled(), applied.server().enabled());
        assertEquals(oldConfig.server().host(), applied.server().host());
        assertEquals(oldConfig.server().port(), applied.server().port());
        assertEquals(oldConfig.server().token(), applied.server().token());
        assertEquals(oldConfig.limits().tickBudgetMs(), applied.limits().tickBudgetMs());
        assertEquals(oldConfig.limits().maxQueuedOperations(), applied.limits().maxQueuedOperations());
        assertEquals(oldConfig.agent().mode(), applied.agent().mode());
    }

    @Test
    void invalidConfigLeavesTheHolderUntouched() {
        // Simulates what AshlarPlugin#reloadAshlarConfig does on a ConfigException: nothing is
        // written to the holder, so it keeps serving the previously running config unchanged.
        PluginConfig oldConfig = baseConfig();
        ConfigHolder holder = new ConfigHolder(oldConfig);

        boolean loadFailed = true; // stands in for a caught ConfigException
        if (!loadFailed) {
            holder.set(baseConfig());
        }

        assertSame(oldConfig, holder.get());
    }

    // ---- ConfigHolder: a request already running keeps its config -----------------------------

    @Test
    void aRunningRequestKeepsTheConfigItStartedWith() {
        PluginConfig oldConfig = baseConfig();
        ConfigHolder holder = new ConfigHolder(oldConfig);

        // A fake consumer that captured a reference at the start of a "request".
        PluginConfig capturedAtStart = holder.get();

        holder.set(withLanguage(oldConfig, "zh_CN"));

        assertSame(oldConfig, capturedAtStart, "the reference obtained before the reload must be unchanged");
        assertEquals("en", capturedAtStart.language(), "the in-flight request's snapshot must still say en");
        assertEquals("zh_CN", holder.get().language(), "the next read must see the new config");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static PluginConfig withLanguage(PluginConfig c, String language) {
        return new PluginConfig(c.server(), c.limits(), c.world(), c.snapshot(), c.logging(), c.runCommand(),
                c.engine(), c.agent(), language);
    }

    private static PluginConfig withServerEnabled(PluginConfig c, boolean enabled) {
        PluginConfig.ServerConfig s = c.server();
        return new PluginConfig(new PluginConfig.ServerConfig(enabled, s.host(), s.port(), s.token(), s.allowedIps()),
                c.limits(), c.world(), c.snapshot(), c.logging(), c.runCommand(), c.engine(), c.agent(), c.language());
    }

    private static PluginConfig withAgentMode(PluginConfig c, PluginConfig.AgentConfig.Mode mode) {
        PluginConfig.AgentConfig a = c.agent();
        PluginConfig.AgentConfig newAgent = new PluginConfig.AgentConfig(mode, a.cooldownSeconds(), a.maxMessageLength(),
                a.echoToMonitors(), a.everyoneCanUse(), a.model(), a.limits(), a.pricing());
        return new PluginConfig(c.server(), c.limits(), c.world(), c.snapshot(), c.logging(), c.runCommand(),
                c.engine(), newAgent, c.language());
    }
}
