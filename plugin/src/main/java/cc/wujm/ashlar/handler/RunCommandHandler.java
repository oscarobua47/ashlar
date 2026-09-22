// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.rpc.ErrorCode;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.rpc.RpcError;
import cc.wujm.ashlar.rpc.RpcHandler;
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
 *
 * <p>Execution ({@link #run}) needs no instance state beyond the command
 * string itself, so it is a public static method rather than a separate
 * service class (plan.md step7), reusable by the in-process tool layer;
 * only the {@code run-command.enabled} gate stays an instance check here.
 */
public final class RunCommandHandler implements RpcHandler {

    private static final int MAX_OUTPUT_LINES = 200;
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;

    private final ConfigHolder configHolder;

    public RunCommandHandler(ConfigHolder configHolder) {
        this.configHolder = configHolder;
    }

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        if (!configHolder.get().runCommand().enabled()) {
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
        return run(command, ctx);
    }

    /** Dispatches {@code command} as the console and captures its synchronous feedback. Runs on the main thread. */
    public static CompletableFuture<JsonElement> run(String command, InvocationContext ctx) {
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

            boolean dispatched = Bukkit.dispatchCommand(sender, command);

            JsonObject result = new JsonObject();
            result.addProperty("command", command);
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
