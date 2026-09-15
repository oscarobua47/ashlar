// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;
import net.rcwalter.ashlar.tool.Tool;
import net.rcwalter.ashlar.tool.ToolRegistry;
import net.rcwalter.ashlar.tool.ToolResult;

import java.util.concurrent.CompletableFuture;

/**
 * {@code tool_call}: runs one {@code mc_*} tool in-process and returns its {@link ToolResult}
 * (plan.md Step 7.2b) - the in-process equivalent of an MCP {@code tools/call}. Params: {@code
 * {"name": "...", "args": {...}}} ({@code args} defaults to {@code {}} when omitted). An unknown
 * tool name is rejected with {@code BAD_REQUEST}, listing the valid names. Runs on the network
 * thread like every handler; the tool's own future completes off-main (7.1's {@code MainThread}
 * guarantees this for anything that touches Bukkit).
 */
public final class ToolCallHandler implements RpcHandler {

    private final ToolRegistry registry;

    public ToolCallHandler(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        if (!params.has("name") || !params.get("name").isJsonPrimitive() || !params.get("name").getAsJsonPrimitive().isString()) {
            return CompletableFuture.failedFuture(new RpcError(ErrorCode.BAD_REQUEST, "\"name\" must be a string"));
        }
        String name = params.get("name").getAsString();
        Tool tool = registry.find(name).orElse(null);
        if (tool == null) {
            return CompletableFuture.failedFuture(new RpcError(ErrorCode.BAD_REQUEST,
                    "unknown tool: " + name + "; valid tools: " + String.join(", ", registry.names())));
        }
        JsonObject args = (params.has("args") && params.get("args").isJsonObject()) ? params.getAsJsonObject("args") : new JsonObject();
        return tool.call(ctx, args).thenApply(ToolResult::toJson);
    }
}
