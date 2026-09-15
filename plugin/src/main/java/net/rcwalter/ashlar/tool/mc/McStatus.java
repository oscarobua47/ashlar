// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.mc;

import com.google.gson.JsonObject;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.RpcHandler;
import net.rcwalter.ashlar.tool.Tool;
import net.rcwalter.ashlar.tool.ToolResult;
import net.rcwalter.ashlar.tool.ToolRunner;
import net.rcwalter.ashlar.tool.ToolSpec;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Pure-Java port of {@code mcp-server/src/tools/mc-status.ts}: calls the plugin's own {@code
 * health} handler directly (no WebSocket round trip) and formats the same connectivity report.
 *
 * <p>The only intentional text difference from the TypeScript tool (docs/private/prompts/
 * step7c-prompt.md &sect;3): the last line, which in TypeScript reports whether the MCP server has a
 * live WebSocket connection to the plugin, becomes {@code "Tool layer: in-process."} here - there
 * is no separate connection to report on when the tool runs inside the plugin's own JVM.
 */
public final class McStatus implements Tool {

    private final ToolSpec spec = ToolSpec.load("mc_status");
    private final RpcHandler healthHandler;

    public McStatus(RpcHandler healthHandler) {
        this.healthHandler = healthHandler;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_status", () -> healthHandler.handle(ctx, new JsonObject()).thenApply(el -> {
            JsonObject r = el.getAsJsonObject();
            return String.join("\n", List.of(
                    "Plugin version: " + r.get("plugin").getAsString(),
                    "Server: " + r.get("server").getAsString() + " (Minecraft " + r.get("minecraft").getAsString() + ")",
                    "Online players: " + r.get("onlinePlayers").getAsInt(),
                    "Queued build operations: " + r.get("queuedOperations").getAsInt(),
                    "Plugin uptime: " + r.get("uptimeSeconds").getAsLong() + "s",
                    "Tool layer: in-process."));
        }));
    }
}
