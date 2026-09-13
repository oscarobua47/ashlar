// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.config.PluginConfig;
import dev.mcaibuilder.plugin.net.ClientSession;
import dev.mcaibuilder.plugin.rpc.ErrorCode;
import dev.mcaibuilder.plugin.rpc.MainThread;
import dev.mcaibuilder.plugin.rpc.RpcError;
import dev.mcaibuilder.plugin.rpc.RpcHandler;
import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;

/**
 * {@code run_command}: executes an arbitrary command as the console (spec
 * &sect;3.3). An escape hatch with full console privileges; disabled via
 * {@code run-command.enabled: false} in {@code config.yml}. A leading {@code
 * /} is stripped if present; console output is not captured (plan.md
 * &sect;3.6, deferred past v1).
 */
public final class RunCommandHandler implements RpcHandler {

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
            boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
            JsonObject result = new JsonObject();
            result.addProperty("command", finalCommand);
            result.addProperty("dispatched", dispatched);
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
