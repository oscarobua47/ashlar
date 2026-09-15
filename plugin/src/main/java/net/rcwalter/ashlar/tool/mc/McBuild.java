// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.rcwalter.ashlar.rpc.ErrorCode;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.rpc.RpcHandler;
import net.rcwalter.ashlar.tool.ArgParse;
import net.rcwalter.ashlar.tool.Tool;
import net.rcwalter.ashlar.tool.ToolArgError;
import net.rcwalter.ashlar.tool.ToolResult;
import net.rcwalter.ashlar.tool.ToolRunner;
import net.rcwalter.ashlar.tool.ToolSpec;
import net.rcwalter.ashlar.tool.text.ToolText;
import net.rcwalter.ashlar.tool.text.WarningText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static net.rcwalter.ashlar.tool.mc.JsonUtil.intArray;
import static net.rcwalter.ashlar.tool.mc.JsonUtil.stringArray;

/**
 * Pure-Java port of {@code mcp-server/src/tools/mc-build.ts}: the full orchestration (optional
 * snapshot, then fills, then blocks, then merged warnings) moved into the plugin. No text
 * differences from the TS tool.
 */
public final class McBuild implements Tool {

    private static final List<String> FILL_MODES = List.of("replace", "keep", "outline", "hollow", "walls");

    private final ToolSpec spec = ToolSpec.load("mc_build");
    private final RpcHandler snapshotHandler;
    private final RpcHandler fillBatchHandler;
    private final RpcHandler setBlocksHandler;

    public McBuild(RpcHandler snapshotHandler, RpcHandler fillBatchHandler, RpcHandler setBlocksHandler) {
        this.snapshotHandler = snapshotHandler;
        this.fillBatchHandler = fillBatchHandler;
        this.setBlocksHandler = setBlocksHandler;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record FillOpArg(int[] from, int[] to, String block, String mode, String filter) {
    }

    record SignArg(List<String> front, List<String> back, String color, Boolean glowing, Boolean waxed) {
    }

    record SparseOpArg(int[] pos, String block, SignArg sign) {
    }

    record Args(String world, List<FillOpArg> fills, List<SparseOpArg> blocks, boolean snapshot, Boolean connect) {
        static Args parse(JsonObject o) {
            String world = ArgParse.optString(o, "world");

            List<FillOpArg> fills = new ArrayList<>();
            if (ArgParse.has(o, "fills")) {
                JsonArray arr = ArgParse.requireArray(o, "fills");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject f = ArgParse.requireObject(arr.get(i), "fills[" + i + "]");
                    int[] from = ArgParse.requireCoords3(f, "from");
                    int[] to = ArgParse.requireCoords3(f, "to");
                    String block = ArgParse.requireString(f, "block");
                    if (block.isEmpty()) {
                        throw new ToolArgError("fills[" + i + "].block: must contain at least 1 character(s)");
                    }
                    String mode = ArgParse.optEnum(f, "mode", FILL_MODES, null);
                    String filter = ArgParse.optString(f, "filter");
                    fills.add(new FillOpArg(from, to, block, mode, filter));
                }
            }

            List<SparseOpArg> blocks = new ArrayList<>();
            if (ArgParse.has(o, "blocks")) {
                JsonArray arr = ArgParse.requireArray(o, "blocks");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject b = ArgParse.requireObject(arr.get(i), "blocks[" + i + "]");
                    int[] pos = ArgParse.requireCoords3(b, "pos");
                    String block = ArgParse.requireString(b, "block");
                    if (block.isEmpty()) {
                        throw new ToolArgError("blocks[" + i + "].block: must contain at least 1 character(s)");
                    }
                    SignArg sign = null;
                    if (ArgParse.has(b, "sign")) {
                        JsonObject s = ArgParse.requireObject(b.get("sign"), "blocks[" + i + "].sign");
                        List<String> front = optSignLines(s, "front");
                        List<String> back = optSignLines(s, "back");
                        String color = ArgParse.optString(s, "color");
                        Boolean glowing = ArgParse.optBooleanNullable(s, "glowing");
                        Boolean waxed = ArgParse.optBooleanNullable(s, "waxed");
                        sign = new SignArg(front, back, color, glowing, waxed);
                    }
                    blocks.add(new SparseOpArg(pos, block, sign));
                }
            }

            if (fills.isEmpty() && blocks.isEmpty()) {
                throw new ToolArgError("\"fills\" and/or \"blocks\" must be provided, with at least one non-empty");
            }

            boolean snapshot = ArgParse.optBoolean(o, "snapshot", false);
            Boolean connect = ArgParse.optBooleanNullable(o, "connect");
            return new Args(world, fills, blocks, snapshot, connect);
        }

        private static List<String> optSignLines(JsonObject signObj, String field) {
            if (!ArgParse.has(signObj, field)) {
                return null;
            }
            JsonArray arr = ArgParse.requireArray(signObj, field);
            if (arr.isEmpty() || arr.size() > 4) {
                throw new ToolArgError("sign." + field + ": must have between 1 and 4 items, got " + arr.size());
            }
            List<String> lines = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                    throw new ToolArgError("sign." + field + ": must contain only strings");
                }
                String line = el.getAsString();
                if (line.length() > 64) {
                    throw new ToolArgError("sign." + field + ": each line must be at most 64 characters");
                }
                lines.add(line);
            }
            return lines;
        }
    }

    private static final class BuildState {
        String snapshotLine;
        List<String> fillsSection;
        String blocksLine;
        final List<WarningText.SupportWarning> warnings = new ArrayList<>();
        boolean warningsTruncated = false;
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_build", () -> {
            Args a = Args.parse(args);
            BuildState state = new BuildState();

            CompletableFuture<Void> step = CompletableFuture.completedFuture(null);

            if (a.snapshot()) {
                step = step.thenCompose(ignored -> snapshotStep(ctx, a, state));
            }
            if (!a.fills().isEmpty()) {
                step = step.thenCompose(ignored -> fillStep(ctx, a, state));
            }
            if (!a.blocks().isEmpty()) {
                step = step.thenCompose(ignored -> blockStep(ctx, a, state));
            }

            return step.thenApply(ignored ->
                    ToolText.buildResultText(state.snapshotLine, state.fillsSection, state.blocksLine, state.warnings, state.warningsTruncated));
        });
    }

    private CompletableFuture<Void> snapshotStep(InvocationContext ctx, Args a, BuildState state) {
        int[][] box = boundingBox(a.fills(), a.blocks());
        JsonObject params = new JsonObject();
        if (a.world() != null) {
            params.addProperty("world", a.world());
        }
        params.add("from", intArray(box[0]));
        params.add("to", intArray(box[1]));
        params.addProperty("label", "mc_build auto-snapshot");
        return snapshotHandler.handle(ctx, params).handle((el, throwable) -> {
            if (throwable != null) {
                Throwable cause = unwrap(throwable);
                if (cause instanceof RpcError rpcError && rpcError.code() == ErrorCode.VOLUME_EXCEEDED) {
                    throw new ToolArgError(ToolText.snapshotVolumeExceededMessage(rpcError.getMessage()));
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new CompletionException(cause);
            }
            JsonObject snap = el.getAsJsonObject();
            state.snapshotLine = ToolText.snapshotLine(snap.get("id").getAsString(), snap.get("volume").getAsLong());
            return (Void) null;
        });
    }

    private CompletableFuture<Void> fillStep(InvocationContext ctx, Args a, BuildState state) {
        JsonObject params = new JsonObject();
        if (a.world() != null) {
            params.addProperty("world", a.world());
        }
        JsonArray ops = new JsonArray();
        for (FillOpArg f : a.fills()) {
            ops.add(fillOpJson(f));
        }
        params.add("ops", ops);
        if (a.connect() != null) {
            params.addProperty("connect", a.connect());
        }
        return fillBatchHandler.handle(ctx, params).thenAccept(el -> {
            JsonObject r = el.getAsJsonObject();
            List<ToolText.FillOpLine> opLines = new ArrayList<>();
            for (JsonElement oe : r.getAsJsonArray("ops")) {
                JsonObject op = oe.getAsJsonObject();
                int index = op.get("index").getAsInt();
                FillOpArg spec = a.fills().get(index);
                opLines.add(new ToolText.FillOpLine(index, spec.from(), spec.to(), spec.block(), op.get("changed").getAsLong(), op.get("volume").getAsLong()));
            }
            state.fillsSection = ToolText.fillsSection(opLines, r.get("totalChanged").getAsLong(), r.get("totalVolume").getAsLong(), r.get("elapsedMs").getAsLong());
            collectWarnings(state, r);
        });
    }

    private CompletableFuture<Void> blockStep(InvocationContext ctx, Args a, BuildState state) {
        JsonObject params = new JsonObject();
        if (a.world() != null) {
            params.addProperty("world", a.world());
        }
        JsonArray blocksJson = new JsonArray();
        for (SparseOpArg b : a.blocks()) {
            blocksJson.add(sparseOpJson(b));
        }
        params.add("blocks", blocksJson);
        if (a.connect() != null) {
            params.addProperty("connect", a.connect());
        }
        return setBlocksHandler.handle(ctx, params).thenAccept(el -> {
            JsonObject r = el.getAsJsonObject();
            state.blocksLine = ToolText.blocksLine(r.get("changed").getAsLong(), r.get("requested").getAsLong(), r.get("elapsedMs").getAsLong());
            collectWarnings(state, r);
        });
    }

    private static void collectWarnings(BuildState state, JsonObject r) {
        for (JsonElement we : r.getAsJsonArray("warnings")) {
            JsonObject w = we.getAsJsonObject();
            JsonArray pos = w.getAsJsonArray("pos");
            state.warnings.add(new WarningText.SupportWarning(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt(),
                    w.get("block").getAsString(), w.get("reason").getAsString()));
        }
        if (r.has("warningsTruncated") && r.get("warningsTruncated").getAsBoolean()) {
            state.warningsTruncated = true;
        }
    }

    private static JsonObject fillOpJson(FillOpArg f) {
        JsonObject o = new JsonObject();
        o.add("from", intArray(f.from()));
        o.add("to", intArray(f.to()));
        o.addProperty("block", f.block());
        if (f.mode() != null) {
            o.addProperty("mode", f.mode());
        }
        if (f.filter() != null) {
            o.addProperty("filter", f.filter());
        }
        return o;
    }

    private static JsonObject sparseOpJson(SparseOpArg b) {
        JsonObject o = new JsonObject();
        o.add("pos", intArray(b.pos()));
        o.addProperty("block", b.block());
        if (b.sign() != null) {
            JsonObject s = new JsonObject();
            if (b.sign().front() != null) {
                s.add("front", stringArray(b.sign().front()));
            }
            if (b.sign().back() != null) {
                s.add("back", stringArray(b.sign().back()));
            }
            if (b.sign().color() != null) {
                s.addProperty("color", b.sign().color());
            }
            if (b.sign().glowing() != null) {
                s.addProperty("glowing", b.sign().glowing());
            }
            if (b.sign().waxed() != null) {
                s.addProperty("waxed", b.sign().waxed());
            }
            o.add("sign", s);
        }
        return o;
    }

    private static int[][] boundingBox(List<FillOpArg> fills, List<SparseOpArg> blocks) {
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (FillOpArg f : fills) {
            consider(min, max, f.from());
            consider(min, max, f.to());
        }
        for (SparseOpArg b : blocks) {
            consider(min, max, b.pos());
        }
        return new int[][]{min, max};
    }

    private static void consider(int[] min, int[] max, int[] p) {
        for (int i = 0; i < 3; i++) {
            if (p[i] < min[i]) {
                min[i] = p[i];
            }
            if (p[i] > max[i]) {
                max[i] = p[i];
            }
        }
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }
}
