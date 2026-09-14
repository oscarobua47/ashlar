// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.engine.HealthService;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.MainThread;
import net.rcwalter.ashlar.rpc.RpcHandler;

import java.util.concurrent.CompletableFuture;

/**
 * {@code health}: connectivity check. Returns plugin/server version, online
 * player count, queue length and uptime. All values that come from Bukkit
 * are read on the main thread via {@link MainThread#call}. Execution lives
 * in {@link HealthService}, shared with the in-process tool layer (plan.md
 * step7).
 */
public final class HealthHandler implements RpcHandler {

    private final HealthService healthService;

    public HealthHandler(HealthService healthService) {
        this.healthService = healthService;
    }

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        return healthService.health(ctx);
    }
}
