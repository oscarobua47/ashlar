// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

import cc.wujm.ashlar.agent.model.ChatMessage;
import cc.wujm.ashlar.agent.model.Usage;

/** One model call's result (mirrors {@code mcp-server/src/agent/provider.ts}'s {@code ChatCompletionResult}). */
public record Reply(ChatMessage message, Usage usage, String finishReason) {
}
