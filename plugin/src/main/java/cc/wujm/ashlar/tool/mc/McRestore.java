// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import cc.wujm.ashlar.tool.text.WarningText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Pure-Java port of {@code mcp-server/src/tools/mc-restore.ts}. No text differences from the TS tool. */
public final class McRestore implements Tool {

    private final ToolSpec spec = ToolSpec.load("mc_restore");
    private final RpcHandler restoreHandler;

    public McRestore(RpcHandler restoreHandler) {
        this.restoreHandler = restoreHandler;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record Args(String id) {
        static Args parse(JsonObject o) {
            String id = ArgParse.requireString(o, "id");
            if (id.isEmpty()) {
                throw new ToolArgError("id: must contain at least 1 character(s)");
            }
            return new Args(id);
        }
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_restore", () -> {
            Args a = Args.parse(args);
            JsonObject params = new JsonObject();
            params.addProperty("id", a.id());
            return restoreHandler.handle(ctx, params).thenApply(el -> {
                JsonObject r = el.getAsJsonObject();
                List<String> lines = new ArrayList<>();
                lines.add("Restored snapshot " + r.get("id").getAsString() + ": " + r.get("restored").getAsLong() + "/"
                        + r.get("volume").getAsLong() + " blocks changed in " + r.get("elapsedMs").getAsLong() + "ms.");
                List<WarningText.SupportWarning> warnings = new ArrayList<>();
                for (JsonElement we : r.getAsJsonArray("warnings")) {
                    JsonObject w = we.getAsJsonObject();
                    JsonArray pos = w.getAsJsonArray("pos");
                    warnings.add(new WarningText.SupportWarning(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt(),
                            w.get("block").getAsString(), w.get("reason").getAsString()));
                }
                boolean truncated = r.has("warningsTruncated") && r.get("warningsTruncated").getAsBoolean();
                lines.addAll(WarningText.formatWarnings(warnings, truncated));
                return String.join("\n", lines);
            });
        });
    }
}
