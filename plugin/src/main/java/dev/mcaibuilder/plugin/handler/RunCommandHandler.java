// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.config.PluginConfig;
import dev.mcaibuilder.plugin.net.ClientSession;
import dev.mcaibuilder.plugin.rpc.ErrorCode;
import dev.mcaibuilder.plugin.rpc.MainThread;
import dev.mcaibuilder.plugin.rpc.RpcError;
import dev.mcaibuilder.plugin.rpc.RpcHandler;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code run_command}: executes an arbitrary command as the console (spec
 * &sect;3.3). An escape hatch with full console privileges; disabled via
 * {@code run-command.enabled: false} in {@code config.yml}. A leading {@code
 * /} is stripped if present.
 *
 * <p>Feedback is captured via {@link Bukkit#createCommandSender(java.util.function.Consumer)},
 * which returns a fully-privileged {@link CommandSender} that forwards every
 * message it is sent to a consumer instead of the console log. Only feedback
 * delivered synchronously during {@link Bukkit#dispatchCommand} is captured;
 * asynchronous/late feedback (e.g. from a command that schedules a delayed
 * task) and anything a command writes only to the server log are not.
 */
public final class RunCommandHandler implements RpcHandler {

    private static final int MAX_OUTPUT_LINES = 200;
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;

    private final PluginConfig config;

    public RunCommandHandler(PluginConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        if (!config.runCommand().enabled()) {
            return CompletableFuture.failedFuture(new RpcError(ErrorCode.DISABLED, "run_command is disabled in config.yml"));
        }
        String command;
        try {
            command = normalize(requireString(params, "command"));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
        if (command.isBlank()) {
            return CompletableFuture.failedFuture(new RpcError(ErrorCode.BAD_REQUEST, "\"command\" must not be empty"));
        }

        String finalCommand = command;
        return MainThread.call(() -> {
            List<String> output = new ArrayList<>();
            boolean[] truncated = {false};
            int[] bytesUsed = {0};
            CommandSender sender = Bukkit.createCommandSender(component -> {
                if (truncated[0]) {
                    return;
                }
                String line = PlainTextComponentSerializer.plainText().serialize(component);
                int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
                if (output.size() >= MAX_OUTPUT_LINES || bytesUsed[0] + lineBytes > MAX_OUTPUT_BYTES) {
                    truncated[0] = true;
                    return;
                }
                output.add(line);
                bytesUsed[0] += lineBytes;
            });

            boolean dispatched = Bukkit.dispatchCommand(sender, finalCommand);

            JsonObject result = new JsonObject();
            result.addProperty("command", finalCommand);
            result.addProperty("dispatched", dispatched);
            JsonArray outputArray = new JsonArray();
            for (String line : output) {
                outputArray.add(line);
            }
            result.add("output", outputArray);
            result.addProperty("truncated", truncated[0]);
            return (JsonElement) result;
        });
    }

    private static String normalize(String raw) {
        return raw.startsWith("/") ? raw.substring(1) : raw;
    }

    private static String requireString(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive() || !obj.get(field).getAsJsonPrimitive().isString()) {
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"" + field + "\" must be a string");
        }
        return obj.get(field).getAsString();
    }
}
