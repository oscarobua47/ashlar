// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent.model;

/**
 * Normalised token usage for one model call (mirrors {@code mcp-server/src/agent/provider.ts}'s
 * {@code CallUsage}). {@code inputTokens} excludes {@code cachedInputTokens}.
 */
public record Usage(long inputTokens, long cachedInputTokens, long outputTokens) {

    public static final Usage ZERO = new Usage(0, 0, 0);

    public Usage plus(Usage other) {
        return new Usage(
                inputTokens + other.inputTokens,
                cachedInputTokens + other.cachedInputTokens,
                outputTokens + other.outputTokens);
    }
}
