// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * The execution path behind {@code health} (connectivity check: plugin/
 * server version, online player count, queue length, uptime), split out of
 * {@code HealthHandler} (plan.md step7) so the same read is reusable by the
 * in-process tool layer (e.g. {@code mc_status}).
 */
public final class HealthService {

    private final JavaPlugin plugin;
    private final Instant startedAt;
    private final TickBudgetExecutor executor;

    public HealthService(JavaPlugin plugin, Instant startedAt, TickBudgetExecutor executor) {
        this.plugin = plugin;
        this.startedAt = startedAt;
        this.executor = executor;
    }

    public CompletableFuture<JsonElement> health(InvocationContext ctx) {
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
