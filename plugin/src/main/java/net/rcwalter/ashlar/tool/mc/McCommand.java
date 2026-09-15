// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.RpcHandler;
import net.rcwalter.ashlar.tool.ArgParse;
import net.rcwalter.ashlar.tool.Tool;
import net.rcwalter.ashlar.tool.ToolArgError;
import net.rcwalter.ashlar.tool.ToolResult;
import net.rcwalter.ashlar.tool.ToolRunner;
import net.rcwalter.ashlar.tool.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Pure-Java port of {@code mcp-server/src/tools/mc-command.ts}. Delegates straight to the plugin's
 * {@code run_command} handler, which already enforces {@code run-command.enabled} and normalizes a
 * leading {@code "/"} - so this tool respects the config gate exactly like the RPC does, with no
 * separate check here.
 */
public final class McCommand implements Tool {

    private final ToolSpec spec = ToolSpec.load("mc_command");
    private final RpcHandler runCommandHandler;

    public McCommand(RpcHandler runCommandHandler) {
        this.runCommandHandler = runCommandHandler;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record Args(String command) {
        static Args parse(JsonObject o) {
            String command = ArgParse.requireString(o, "command");
            if (command.isEmpty()) {
                throw new ToolArgError("command: must contain at least 1 character(s)");
            }
            return new Args(command);
        }
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_command", () -> {
            Args a = Args.parse(args);
            JsonObject params = new JsonObject();
            params.addProperty("command", a.command());
            return runCommandHandler.handle(ctx, params).thenApply(el -> {
                JsonObject r = el.getAsJsonObject();
                List<String> lines = new ArrayList<>();
                JsonArray output = r.getAsJsonArray("output");
                if (output.size() > 0) {
                    List<String> outLines = new ArrayList<>();
                    for (JsonElement o : output) {
                        outLines.add(o.getAsString());
                    }
                    lines.add(String.join("\n", outLines));
                } else {
                    lines.add("(command produced no feedback)");
                }
                if (!r.get("dispatched").getAsBoolean()) {
                    lines.add("Warning: command \"" + r.get("command").getAsString()
                            + "\" was not dispatched (rejected by the server's command dispatcher).");
                }
                if (r.has("truncated") && r.get("truncated").getAsBoolean()) {
                    lines.add("[output truncated]");
                }
                return String.join("\n", lines);
            });
        });
    }
}
