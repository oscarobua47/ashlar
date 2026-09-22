// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.config;

/**
 * The seam {@link cc.wujm.ashlar.command.AshlarCommand} calls into for {@code /ashlar reload}
 * (step8j-prompt.md), implemented by {@code AshlarPlugin} so the command class itself stays free of
 * Bukkit's {@code JavaPlugin.reloadConfig()}/{@code getConfig()} and of every other component
 * ({@code Cooldown}, {@code SnapshotStore}, {@code OperationLog}, {@code AgentService}, the {@code
 * ashlar.use} permission default) that also needs to react to a reload.
 */
@FunctionalInterface
public interface ConfigReloader {

    /** Must be called from the main thread ({@code reloadConfig()}/{@code getConfig()} are Bukkit). */
    ReloadOutcome reload();
}
