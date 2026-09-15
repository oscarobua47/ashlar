// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.RpcHandler;
import net.rcwalter.ashlar.tool.ToolRegistry;
import net.rcwalter.ashlar.tool.ToolSpec;

import java.util.concurrent.CompletableFuture;

/**
 * {@code tool_catalog}: returns every registered {@code mc_*} tool's client-facing spec (plan.md
 * Step 7.2b) - the in-process equivalent of an MCP {@code tools/list} call, so the Node adapter
 * (Step 7.3) can register the plugin's own tool definitions instead of shipping its own copies.
 * Params: {@code {}}. The result also carries {@code instructions}: the server-level text an MCP
 * adapter shows the model (from {@code resources/tools/instructions.txt}), so no Ashlar-specific
 * wording has to live outside the plugin.
 */
public final class ToolCatalogHandler implements RpcHandler {

    private final ToolRegistry registry;

    public ToolCatalogHandler(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        JsonArray tools = new JsonArray();
        for (ToolSpec spec : registry.catalog()) {
            tools.add(spec.toJson());
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        String instructions = registry.instructions();
        if (instructions != null) {
            result.addProperty("instructions", instructions);
        }
        return CompletableFuture.completedFuture(result);
    }
}
