// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rcwalter.ashlar.config.PluginConfig;
import net.rcwalter.ashlar.net.ClientSession;
import net.rcwalter.ashlar.player.Monitors;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.SessionRpcHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code send_message}: delivers a chat message to one online player. Used
 * by a connected agent process to reply to an in-game {@code /ashlar}
 * request (step6a-prompt.md); nothing here is AI-specific, it is a plain
 * "send this text to this player" primitive. Params: {@code {"player":
 * "<uuid or exact name>", "text": "...", "kind": "progress"|"final"}}
 * ({@code kind} is optional, defaults to {@code "progress"}).
 *
 * <p>When {@code kind} is {@code "final"} and {@code agent.echo-to-monitors}
 * is on, each line is additionally delivered to online players with
 * {@code ashlar.monitor} (other than the target) prefixed with
 * {@code "[Ashlar -> <name>] "} instead of {@code "[Ashlar] "}
 * (step6d-prompt.md); progress lines are never echoed. Not recorded as
 * changing blocks in {@link net.rcwalter.ashlar.log.OperationLog} (nothing
 * to extract there; {@code extractBlocksChanged} already yields 0 for a
 * result with none of its known fields).
 *
 * <p>Grouped with {@code subscribe} as the other {@link SessionRpcHandler}
 * exception to the {@code InvocationContext}-based {@code RpcHandler}
 * (plan.md step7): a transport/messaging primitive, not world-related, kept
 * on the old signature even though its body does not touch {@code session}.
 */
public final class SendMessageHandler implements SessionRpcHandler {

    private static final int MAX_TEXT_LENGTH = 4000;

    private final PluginConfig config;

    public SendMessageHandler(PluginConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        String playerRef;
        String text;
        boolean isFinal;
        try {
            playerRef = requireString(params, "player");
            text = requireString(params, "text");
            isFinal = requireKind(params);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return CompletableFuture.failedFuture(
                    new RpcError(ErrorCode.BAD_REQUEST, "\"text\" must be at most " + MAX_TEXT_LENGTH + " characters"));
        }

        String finalPlayerRef = playerRef;
        String finalText = text;
        boolean finalKind = isFinal;
        return MainThread.call(() -> {
            Player player = resolvePlayer(finalPlayerRef);
            if (player == null) {
                throw new RpcError(ErrorCode.BAD_REQUEST, "player not online: " + finalPlayerRef);
            }
            List<Player> monitors = finalKind && config.agent().echoToMonitors()
                    ? Monitors.onlineExcept(player)
                    : List.of();
            Component monitorPrefix = Component.text("[Ashlar -> " + player.getName() + "] ", NamedTextColor.GOLD);

            int lines = 0;
            for (String line : finalText.split("\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                Component body = Component.text(line, NamedTextColor.WHITE);
                player.sendMessage(Component.text("[Ashlar] ", NamedTextColor.GOLD).append(body));
                for (Player monitor : monitors) {
                    monitor.sendMessage(monitorPrefix.append(body));
                }
                lines++;
            }
            JsonObject result = new JsonObject();
            result.addProperty("delivered", true);
            result.addProperty("lines", lines);
            result.addProperty("monitors", monitors.size());
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

    /** Returns whether {@code kind} is {@code "final"}; absent defaults to {@code "progress"} (false). */
    private static boolean requireKind(JsonObject obj) {
        if (!obj.has("kind")) {
            return false;
        }
        JsonElement el = obj.get("kind");
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"kind\" must be a string");
        }
        String kind = el.getAsString();
        if (kind.equals("progress")) {
            return false;
        }
        if (kind.equals("final")) {
            return true;
        }
        throw new RpcError(ErrorCode.BAD_REQUEST, "\"kind\" must be \"progress\" or \"final\"");
    }
}
