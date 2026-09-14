// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.net.ClientSession;
import net.rcwalter.ashlar.player.PlayerJson;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * {@code players}: read-only roster of online players, giving spatial
 * context for requests like "build here" or "put it in front of me". Params:
 * {@code {}}. All Bukkit access happens on the main thread via
 * {@link MainThread#call}; not recorded in {@link net.rcwalter.ashlar.log.OperationLog}
 * since it does not touch the world. Per-player JSON is built by
 * {@link PlayerJson#describe}, shared with the {@code chat} event
 * (step6a-prompt.md).
 */
public final class PlayersHandler implements RpcHandler {

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        return MainThread.call(() -> {
            JsonArray players = new JsonArray();
            for (Player player : Bukkit.getOnlinePlayers()) {
                players.add(PlayerJson.describe(player));
            }
            JsonObject result = new JsonObject();
            result.addProperty("count", players.size());
            result.add("players", players);
            return (JsonElement) result;
        });
    }
}
