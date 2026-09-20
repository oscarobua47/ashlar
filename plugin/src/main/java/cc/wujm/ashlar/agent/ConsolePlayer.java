// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

import java.util.UUID;

/**
 * Identity used by the console command {@code ashlar simulate} (docs/private/prompts/
 * step8b-prompt.md): a synthetic player-shaped request built from console input flows through
 * {@link AgentService} exactly like a real player's - usage accounting, history and the
 * per-player queue all key off this fixed UUID. {@link cc.wujm.ashlar.player.ChatOut}
 * special-cases this UUID to log to the console instead of trying to deliver a chat message to a
 * (nonexistent) online player.
 */
public final class ConsolePlayer {

    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String NAME = "Console";

    private ConsolePlayer() {
    }
}
