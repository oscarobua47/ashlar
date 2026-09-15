// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import net.rcwalter.ashlar.agent.model.ChatMessage;

import java.util.List;

/** Per-player remembered chat history (mirrors {@code mcp-server/src/agent/history.ts}'s {@code History}). */
public interface History {

    /** Returns this player's remembered messages, oldest first, evicting the player if idle past the TTL. */
    List<ChatMessage> get(String uuid);

    /** Appends one exchange's messages (a full player request: user message through final assistant reply) and trims. */
    void append(String uuid, List<ChatMessage> exchange);
}
