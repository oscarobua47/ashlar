// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcaibuilder.plugin.engine.TickBudgetExecutor;
import dev.mcaibuilder.plugin.net.ClientSession;
import dev.mcaibuilder.plugin.rpc.MainThread;
import dev.mcaibuilder.plugin.rpc.RpcHandler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * {@code health}: connectivity check. Returns plugin/server version, online
 * player count, queue length and uptime. All values that come from Bukkit
 * are read on the main thread via {@link MainThread#call}.
 */
public final class HealthHandler implements RpcHandler {

    private final JavaPlugin plugin;
    private final Instant startedAt;
    private final TickBudgetExecutor executor;

    public HealthHandler(JavaPlugin plugin, Instant startedAt, TickBudgetExecutor executor) {
        this.plugin = plugin;
        this.startedAt = startedAt;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<JsonElement> handle(ClientSession session, JsonElement id, JsonObject params) {
        return MainThread.call(() -> {
            JsonObject result = new JsonObject();
            result.addProperty("plugin", plugin.getPluginMeta().getVersion());
            result.addProperty("server", Bukkit.getName() + " " + Bukkit.getVersion());
            result.addProperty("minecraft", Bukkit.getBukkitVersion());
            result.addProperty("onlinePlayers", Bukkit.getOnlinePlayers().size());
            result.addProperty("queuedOperations", executor.queuedCount());
            result.addProperty("uptimeSeconds", Duration.between(startedAt, Instant.now()).getSeconds());
            return (JsonElement) result;
        });
    }
}
