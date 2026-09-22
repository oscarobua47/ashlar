// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure logic behind {@code /ashlar reload} (step8j-prompt.md &sect;A/B): which keys are "cold"
 * (need a restart) and how to build the config that is actually applied on a successful reload -
 * every hot field from the freshly-loaded config, every cold field pinned to whatever the plugin
 * started with (so a cold key in {@code config.yml} never takes effect until a restart, even though
 * the file on disk already has the new value; {@link #coldChanges} still reports it so the operator
 * knows to restart). No Bukkit here, so this is directly unit-testable.
 */
public final class ConfigReload {

    private ConfigReload() {
    }

    /**
     * The cold keys (spec &sect;A): {@code mode} (derived from {@code server.enabled} +
     * {@code agent.mode}, since the top-level {@code mode} key itself is not stored verbatim on
     * {@link PluginConfig}), {@code server.host}, {@code server.port}, {@code server.token},
     * {@code limits.tick-budget-ms} and {@code limits.max-queued-operations} (both captured once by
     * {@code TickBudgetExecutor} at construction). Returns the dotted key names that differ between
     * {@code oldConfig} and {@code newConfig}, in the fixed order above.
     */
    public static List<String> coldChanges(PluginConfig oldConfig, PluginConfig newConfig) {
        List<String> changed = new ArrayList<>();
        if (oldConfig.server().enabled() != newConfig.server().enabled()
                || oldConfig.agent().mode() != newConfig.agent().mode()) {
            changed.add("mode");
        }
        if (!oldConfig.server().host().equals(newConfig.server().host())) {
            changed.add("server.host");
        }
        if (oldConfig.server().port() != newConfig.server().port()) {
            changed.add("server.port");
        }
        if (!oldConfig.server().token().equals(newConfig.server().token())) {
            changed.add("server.token");
        }
        if (oldConfig.limits().tickBudgetMs() != newConfig.limits().tickBudgetMs()) {
            changed.add("limits.tick-budget-ms");
        }
        if (oldConfig.limits().maxQueuedOperations() != newConfig.limits().maxQueuedOperations()) {
            changed.add("limits.max-queued-operations");
        }
        return changed;
    }

    /**
     * The config to actually apply after a successful {@code PluginConfig.load} of the reloaded
     * file: every hot field taken from {@code newConfig}, every cold field taken from {@code
     * oldConfig} (see the class javadoc). {@code world}/{@code snapshot}/{@code logging}/{@code
     * run-command}/{@code engine}/{@code language} are fully hot, so those sections are taken from
     * {@code newConfig} wholesale; {@code server}/{@code limits}/{@code agent} are a mix, built
     * field-by-field below.
     */
    public static PluginConfig applyHotOnly(PluginConfig oldConfig, PluginConfig newConfig) {
        PluginConfig.ServerConfig mergedServer = new PluginConfig.ServerConfig(
                oldConfig.server().enabled(), // mode-derived: cold
                oldConfig.server().host(), // cold
                oldConfig.server().port(), // cold
                oldConfig.server().token(), // cold
                newConfig.server().allowedIps()); // hot: checked per connection

        PluginConfig.LimitsConfig mergedLimits = new PluginConfig.LimitsConfig(
                newConfig.limits().maxBlocksPerOperation(),
                newConfig.limits().maxReadVolume(),
                oldConfig.limits().tickBudgetMs(), // cold: TickBudgetExecutor#tick
                oldConfig.limits().maxQueuedOperations(), // cold: TickBudgetExecutor#submit
                newConfig.limits().maxChunksPerOperation(),
                newConfig.limits().maxFlowingLiquidsPerOperation());

        PluginConfig.AgentConfig mergedAgent = new PluginConfig.AgentConfig(
                oldConfig.agent().mode(), // mode-derived: cold
                newConfig.agent().cooldownSeconds(),
                newConfig.agent().maxMessageLength(),
                newConfig.agent().echoToMonitors(),
                newConfig.agent().everyoneCanUse(),
                newConfig.agent().model(),
                newConfig.agent().limits(),
                newConfig.agent().pricing());

        return new PluginConfig(
                mergedServer,
                mergedLimits,
                newConfig.world(),
                newConfig.snapshot(),
                newConfig.logging(),
                newConfig.runCommand(),
                newConfig.engine(),
                mergedAgent,
                newConfig.language());
    }
}
