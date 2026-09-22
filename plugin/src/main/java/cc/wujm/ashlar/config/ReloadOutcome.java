// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.config;

import java.util.List;

/**
 * The result of one {@code /ashlar reload} (step8j-prompt.md): either {@code error} is non-null
 * (the reloaded {@code config.yml} failed {@link PluginConfig#load} validation, so nothing was
 * applied and the previously running config is untouched), or the reload succeeded and {@code
 * coldChanges} lists the dotted key names ({@link ConfigReload#coldChanges}) that still need a
 * server restart to take effect - empty when every changed key was hot.
 */
public record ReloadOutcome(boolean success, String error, List<String> coldChanges) {

    public static ReloadOutcome ok(List<String> coldChanges) {
        return new ReloadOutcome(true, null, coldChanges);
    }

    public static ReloadOutcome failed(String error) {
        return new ReloadOutcome(false, error, List.of());
    }
}
