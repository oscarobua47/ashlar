// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import net.rcwalter.ashlar.agent.model.ChatMessage;
import net.rcwalter.ashlar.agent.model.Usage;

/** One model call's result (mirrors {@code mcp-server/src/agent/provider.ts}'s {@code ChatCompletionResult}). */
public record Reply(ChatMessage message, Usage usage, String finishReason) {
}
