// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.config;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The live, swappable {@link PluginConfig} (step8j-prompt.md, {@code /ashlar reload}). Every "hot"
 * consumer holds a reference to this class instead of a plain {@link PluginConfig} field, and calls
 * {@link #get()} at the point it actually needs a value rather than once at construction - that is
 * the entire mechanism by which a reload takes effect for them: the next call after {@link
 * #set(PluginConfig)} sees the new config, a call already in flight keeps whatever immutable {@link
 * PluginConfig} instance it already read (records are immutable, so a reference obtained before a
 * reload can never observe a half-applied change).
 *
 * <p>{@link #set} is only ever called from the main thread (the {@code /ashlar reload} command
 * executor), but {@link #get} may be called from any thread (network threads, the agent's virtual
 * threads), hence the {@link AtomicReference} rather than a plain field.
 */
public final class ConfigHolder {

    private final AtomicReference<PluginConfig> ref;

    public ConfigHolder(PluginConfig initial) {
        this.ref = new AtomicReference<>(initial);
    }

    public PluginConfig get() {
        return ref.get();
    }

    public void set(PluginConfig config) {
        ref.set(config);
    }
}
