// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.mcaibuilder.plugin.rpc;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The single allowed entry point from any off-main-thread code (the
 * WebSocket network threads, in particular) into the Bukkit/Paper API.
 *
 * <p>Every call into Bukkit (server version, player count, scheduler,
 * world/block access, etc.) MUST go through {@link #call(Supplier)}, which
 * schedules the work onto the main thread via
 * {@link org.bukkit.scheduler.BukkitScheduler#runTask} and completes a
 * {@link CompletableFuture} with the result. The only exception is
 * {@code plugin.getLogger()}, which is thread-safe and may be used anywhere.
 */
public final class MainThread {

    private static volatile Plugin plugin;

    private MainThread() {
    }

    /** Must be called once, from {@code onEnable}, before any RPC handler runs. */
    public static void init(Plugin owningPlugin) {
        plugin = owningPlugin;
    }

    public static <T> CompletableFuture<T> call(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Plugin currentPlugin = plugin;
        if (currentPlugin == null || !currentPlugin.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("MainThread is not initialized or plugin is disabled"));
            return future;
        }
        Bukkit.getScheduler().runTask(currentPlugin, () -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
