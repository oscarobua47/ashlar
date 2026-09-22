// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.engine.text.FontRenderException;
import cc.wujm.ashlar.engine.text.TextExpand;
import cc.wujm.ashlar.rpc.ErrorCode;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcError;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import cc.wujm.ashlar.tool.text.ToolText;
import cc.wujm.ashlar.tool.text.WarningText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static cc.wujm.ashlar.tool.mc.JsonUtil.intArray;
import static cc.wujm.ashlar.tool.mc.JsonUtil.stringArray;

/**
 * Pure-Java port of {@code mcp-server/src/tools/mc-build.ts}: the full orchestration (optional
 * snapshot, then fills, then blocks, then merged warnings) moved into the plugin. No text
 * differences from the TS tool.
 */
public final class McBuild implements Tool {

    private static final List<String> FILL_MODES = List.of("replace", "keep", "outline", "hollow", "walls");
    private static final List<String> LIQUIDS_MODES = List.of("static", "flow");

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

    /** One {@code text} entry, plus its geometry already expanded via {@link TextExpand#expand} at parse time. */
    record TextArg(String text, int[] pos, String block, String background, String facing, int scale, int spacing,
            String align, TextExpand.Result expanded) {
    }

    record Args(String world, List<FillOpArg> fills, List<SparseOpArg> blocks, List<TextArg> text, boolean snapshot,
            Boolean connect, String liquids) {
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

            List<TextArg> text = new ArrayList<>();
            if (ArgParse.has(o, "text")) {
                JsonArray arr = ArgParse.requireArray(o, "text");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject t = ArgParse.requireObject(arr.get(i), "text[" + i + "]");
                    text.add(parseTextEntry(i, t));
                }
            }

            if (fills.isEmpty() && blocks.isEmpty() && text.isEmpty()) {
                throw new ToolArgError(
                        "\"fills\", \"blocks\" and/or \"text\" must be provided, with at least one non-empty");
            }

            boolean snapshot = ArgParse.optBoolean(o, "snapshot", false);
            Boolean connect = ArgParse.optBooleanNullable(o, "connect");
            String liquids = ArgParse.optEnum(o, "liquids", LIQUIDS_MODES, null);
            return new Args(world, fills, blocks, text, snapshot, connect, liquids);
        }

        private static TextArg parseTextEntry(int i, JsonObject t) {
            String rawText = ArgParse.requireString(t, "text");
            int[] pos = ArgParse.requireCoords3(t, "pos");
            String block = ArgParse.requireString(t, "block");
            if (block.isEmpty()) {
                throw new ToolArgError("text[" + i + "].block: must contain at least 1 character(s)");
            }
            String background = ArgParse.optString(t, "background");
            String facing = ArgParse.optEnum(t, "facing", TextExpand.FACINGS, "south");
            Integer scaleOpt = ArgParse.optInt(t, "scale");
            int scale = scaleOpt != null ? scaleOpt : 1;
            if (scale < TextExpand.MIN_SCALE || scale > TextExpand.MAX_SCALE) {
                throw new ToolArgError("text[" + i + "].scale: must be between " + TextExpand.MIN_SCALE + " and "
                        + TextExpand.MAX_SCALE + ", got " + scale);
            }
            Integer spacingOpt = ArgParse.optInt(t, "spacing");
            int spacing = spacingOpt != null ? spacingOpt : 1;
            if (spacing < TextExpand.MIN_SPACING || spacing > TextExpand.MAX_SPACING) {
                throw new ToolArgError("text[" + i + "].spacing: must be between " + TextExpand.MIN_SPACING + " and "
                        + TextExpand.MAX_SPACING + ", got " + spacing);
            }
            String align = ArgParse.optEnum(t, "align", TextExpand.ALIGNS, TextExpand.ALIGN_LEFT);
            TextExpand.Result expanded;
            try {
                expanded = TextExpand.expand(rawText, pos, facing, scale, spacing, align);
            } catch (FontRenderException e) {
                throw new ToolArgError(e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new ToolArgError("text[" + i + "]: " + e.getMessage());
            }
            return new TextArg(rawText.trim(), pos, block, background, facing, scale, spacing, align, expanded);
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
        List<String> textSection;
        String blocksLine;
        final List<WarningText.SupportWarning> warnings = new ArrayList<>();
        boolean warningsTruncated = false;
        long chestsPaired = 0;
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
            if (!a.text().isEmpty()) {
                step = step.thenCompose(ignored -> textStep(ctx, a, state));
            }
            if (!a.blocks().isEmpty()) {
                step = step.thenCompose(ignored -> blockStep(ctx, a, state));
            }

            return step.thenApply(ignored ->
                    ToolText.buildResultText(state.snapshotLine, state.fillsSection, state.textSection, state.blocksLine,
                            state.chestsPaired, state.warnings, state.warningsTruncated));
        });
    }

    private CompletableFuture<Void> snapshotStep(InvocationContext ctx, Args a, BuildState state) {
        int[][] box = boundingBox(a.fills(), a.blocks(), a.text());
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
        if (a.liquids() != null) {
            params.addProperty("liquids", a.liquids());
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
            collectChestsPaired(state, r);
            collectWarnings(state, r);
        });
    }

    /**
     * Sends every {@code text} entry's expanded geometry ({@link TextExpand.Result}, computed
     * already at parse time) as a single {@code fill_batch} call, then reassembles the per-entry
     * "Text:" report from that call's per-op results. Reuses {@code fillBatchHandler} exactly like
     * {@link #fillStep} - the expanded blocks count against the same limits, get the same
     * connect/support-warning handling, and use the same block-string parsing/errors.
     */
    private CompletableFuture<Void> textStep(InvocationContext ctx, Args a, BuildState state) {
        JsonObject params = new JsonObject();
        if (a.world() != null) {
            params.addProperty("world", a.world());
        }
        JsonArray ops = new JsonArray();
        int[] opsPerEntry = new int[a.text().size()];
        for (int i = 0; i < a.text().size(); i++) {
            TextArg t = a.text().get(i);
            int count = 0;
            if (t.background() != null) {
                JsonObject bgOp = new JsonObject();
                bgOp.add("from", intArray(t.expanded().bboxMin()));
                bgOp.add("to", intArray(t.expanded().bboxMax()));
                bgOp.addProperty("block", t.background());
                ops.add(bgOp);
                count++;
            }
            for (TextExpand.Run run : t.expanded().inkRuns()) {
                JsonObject op = new JsonObject();
                op.add("from", intArray(run.from()));
                op.add("to", intArray(run.to()));
                op.addProperty("block", t.block());
                ops.add(op);
                count++;
            }
            opsPerEntry[i] = count;
        }
        params.add("ops", ops);
        if (a.connect() != null) {
            params.addProperty("connect", a.connect());
        }
        if (a.liquids() != null) {
            params.addProperty("liquids", a.liquids());
        }
        return fillBatchHandler.handle(ctx, params).thenAccept(el -> {
            JsonObject r = el.getAsJsonObject();
            JsonArray opsResult = r.getAsJsonArray("ops");
            List<ToolText.TextEntryLine> lines = new ArrayList<>();
            int cursor = 0;
            for (int i = 0; i < a.text().size(); i++) {
                TextArg t = a.text().get(i);
                long entryChanged = 0;
                long entryVolume = 0;
                for (int k = 0; k < opsPerEntry[i]; k++) {
                    JsonObject op = opsResult.get(cursor + k).getAsJsonObject();
                    entryChanged += op.get("changed").getAsLong();
                    entryVolume += op.get("volume").getAsLong();
                }
                cursor += opsPerEntry[i];
                lines.add(new ToolText.TextEntryLine(i, t.text(), t.expanded().bboxMin(), t.expanded().bboxMax(),
                        t.expanded().widthBlocks(), t.expanded().heightBlocks(), entryChanged, entryVolume));
            }
            state.textSection = ToolText.textSection(lines, r.get("totalChanged").getAsLong(),
                    r.get("totalVolume").getAsLong(), r.get("elapsedMs").getAsLong());
            collectChestsPaired(state, r);
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
        if (a.liquids() != null) {
            params.addProperty("liquids", a.liquids());
        }
        return setBlocksHandler.handle(ctx, params).thenAccept(el -> {
            JsonObject r = el.getAsJsonObject();
            state.blocksLine = ToolText.blocksLine(r.get("changed").getAsLong(), r.get("requested").getAsLong(), r.get("elapsedMs").getAsLong());
            collectChestsPaired(state, r);
            collectWarnings(state, r);
        });
    }

    private static void collectChestsPaired(BuildState state, JsonObject r) {
        if (r.has("chestsPaired")) {
            state.chestsPaired += r.get("chestsPaired").getAsLong();
        }
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

    private static int[][] boundingBox(List<FillOpArg> fills, List<SparseOpArg> blocks, List<TextArg> text) {
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (FillOpArg f : fills) {
            consider(min, max, f.from());
            consider(min, max, f.to());
        }
        for (SparseOpArg b : blocks) {
            consider(min, max, b.pos());
        }
        for (TextArg t : text) {
            consider(min, max, t.expanded().bboxMin());
            consider(min, max, t.expanded().bboxMax());
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
