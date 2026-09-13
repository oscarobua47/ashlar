// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.config;

/** Thrown when {@code config.yml} fails a fatal validation check at startup. */
public final class ConfigException extends Exception {
    public ConfigException(String message) {
        super(message);
    }
}
