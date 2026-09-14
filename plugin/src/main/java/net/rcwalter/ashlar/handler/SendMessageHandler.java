// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rcwalter.ashlar.net.ClientSession;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code send_message}: delivers a chat message to one online player. Used
 * by a connected agent process to reply to an in-game {@code /ashlar}
 * request (step6a-prompt.md); nothing here is AI-specific, it is a plain
 * "send this text to this player" primitive. Params: {@code {"player":
 * "<uuid or exact name>", "text": "..."}}. Not recorded as changing blocks
 * in {@link net.rcwalter.ashlar.log.OperationLog} (nothing to extract
 * there; {@code extractBlocksChanged} already yields 0 for a result with
 * none of its known fields).
 */
public final class SendMessageHandler implements RpcHandler {

    private static final int MAX_TEXT_LENGTH = 4000;

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        String playerRef;
        String text;
        try {
            playerRef = requireString(params, "player");
            text = requireString(params, "text");
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return CompletableFuture.failedFuture(
                    new RpcError(ErrorCode.BAD_REQUEST, "\"text\" must be at most " + MAX_TEXT_LENGTH + " characters"));
        }

        String finalPlayerRef = playerRef;
        String finalText = text;
        return MainThread.call(() -> {
            Player player = resolvePlayer(finalPlayerRef);
            if (player == null) {
                throw new RpcError(ErrorCode.BAD_REQUEST, "player not online: " + finalPlayerRef);
            }
            int lines = 0;
            for (String line : finalText.split("\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                player.sendMessage(Component.text("[Ashlar] ", NamedTextColor.GOLD).append(Component.text(line, NamedTextColor.WHITE)));
                lines++;
            }
            JsonObject result = new JsonObject();
            result.addProperty("delivered", true);
            result.addProperty("lines", lines);
            return (JsonElement) result;
        });
    }

    /** Resolves by UUID when {@code ref} parses as one, otherwise by exact (case-sensitive) player name. */
    private static Player resolvePlayer(String ref) {
        try {
            UUID uuid = UUID.fromString(ref);
            return Bukkit.getPlayer(uuid);
        } catch (IllegalArgumentException notAUuid) {
            return Bukkit.getPlayerExact(ref);
        }
    }

    private static String requireString(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive() || !obj.get(field).getAsJsonPrimitive().isString()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be a string");
        }
        return obj.get(field).getAsString();
    }
}
