// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

import java.util.UUID;

/**
 * Where {@link AgentService} sends progress lines and final replies (mirrors the plugin's
 * {@code send_message} RPC, but as a direct in-process call with no session/JSON round trip):
 * {@link cc.wujm.ashlar.player.ChatOut} is the real implementation; tests supply a recording
 * fake. {@code finalKind} is {@code true} only for a finished reply's chunks, matching {@code
 * send_message}'s {@code kind} field - the plugin uses it to decide what {@code ashlar.monitor}
 * players get to see.
 */
public interface Outbox {

    void send(UUID uuid, String text, boolean finalKind);
}
