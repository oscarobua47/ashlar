// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.rcwalter.ashlar.agent.model.ChatMessage;
import net.rcwalter.ashlar.agent.model.ContentPart;
import net.rcwalter.ashlar.agent.model.ToolCall;
import net.rcwalter.ashlar.agent.model.ToolDef;
import net.rcwalter.ashlar.agent.model.Usage;
import net.rcwalter.ashlar.rpc.InvocationContext;
import net.rcwalter.ashlar.tool.ContentBlock;
import net.rcwalter.ashlar.tool.Tool;
import net.rcwalter.ashlar.tool.ToolRegistry;
import net.rcwalter.ashlar.tool.ToolResult;
import net.rcwalter.ashlar.tool.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Drives one player request through the model + {@code mc_*} tools loop (pure-Java port of {@code
 * mcp-server/src/agent/runner.ts}'s {@code runRequest}): builds the message list (system prompt,
 * remembered history, then the user's text with a {@code [context]} suffix), repeatedly calls the
 * model, executes any tool calls it returns via the given {@link ToolRegistry}, and stops either
 * on a plain-text reply, on the tool budget being exhausted, or on {@code cancelled} becoming true
 * between steps. Unlike the TypeScript version, this class does not touch player history itself:
 * the caller passes in the messages to remember (already fetched from a {@link History}) and
 * receives the full exchange back in {@link RunResult#exchange()} to append itself, once it has
 * decided the request was not cancelled - that decoupling is what let this class drop its
 * dependency on the Bukkit-free-but-still-stateful {@code History} interface.
 */
public final class AgentRunner {

    private static final List<String> SHORT_ARG_KEYS = List.of("from", "to", "view", "action", "liquids");
    private static final long POLL_MS = 50;

    private final ModelApi modelApi;
    private final ToolRegistry toolRegistry;
    private final List<String> allowedToolNames;
    private final int maxToolCalls;
    private final String imageDetail;
    private final Optional<String> systemPromptExtra;

    public AgentRunner(ModelApi modelApi, ToolRegistry toolRegistry, Set<String> allowedTools, int maxToolCalls,
                        String imageDetail, Optional<String> systemPromptExtra) {
        this.modelApi = modelApi;
        this.toolRegistry = toolRegistry;
        List<String> names = new ArrayList<>();
        for (ToolSpec spec : toolRegistry.catalog()) {
            if (allowedTools.contains(spec.name())) {
                names.add(spec.name());
            }
        }
        this.allowedToolNames = List.copyOf(names);
        this.maxToolCalls = maxToolCalls;
        this.imageDetail = imageDetail;
        this.systemPromptExtra = systemPromptExtra;
    }

    /** The subset of the plugin's chat event's player object the runner needs. */
    public record PlayerInfo(String name, String uuid, String world, int[] pos, String facing, int[] inFront, String gameMode,
            String lookingAt) {
    }

    public record RunRequest(PlayerInfo player, String text, List<ChatMessage> history, BooleanSupplier cancelled,
                              Consumer<String> onProgress, Consumer<Usage> onTurnUsage) {
    }

    public record RunResult(String text, Usage usage, int toolCalls, List<ChatMessage> exchange) {
    }

    private record ImageEntry(String toolName, String toolCallId, List<ContentBlock.Image> images) {
    }

    private record ToolCallOutcome(String text, List<ContentBlock.Image> images, boolean isError) {
    }

    public RunResult run(RunRequest req) {
        Usage usage = Usage.ZERO;

        int[] pos = req.player().pos();
        int[] inFront = req.player().inFront();
        String contextLine = req.text() + "\n\n[context] player " + req.player().name() + " in world " + req.player().world()
                + " at pos " + joinInts(pos) + " (ground at y=" + (pos[1] - 1) + ") facing " + req.player().facing()
                + ", block in front " + joinInts(inFront)
                + (req.player().lookingAt() != null ? ", looking at " + req.player().lookingAt() : ", looking at nothing within 16 blocks")
                + ", gamemode " + req.player().gameMode();

        ChatMessage rawUserMessage = ChatMessage.user(req.text());
        ChatMessage contextUserMessage = ChatMessage.user(contextLine);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SystemPrompt.build(systemPromptExtra)));
        messages.addAll(req.history());
        messages.add(contextUserMessage);

        List<ChatMessage> exchange = new ArrayList<>();
        exchange.add(rawUserMessage);

        List<ToolDef> tools = buildToolDefs();

        int toolCallCount = 0;
        boolean budgetExhausted = false;
        int callIndex = 0;

        for (;;) {
            if (req.cancelled().getAsBoolean()) {
                return new RunResult("Cancelled.", usage, toolCallCount, exchange);
            }

            if (budgetExhausted) {
                ChatMessage note = ChatMessage.user("[system] Tool budget exhausted - summarise what was done and stop.");
                messages.add(note);
                exchange.add(note);
            }

            Reply reply;
            try {
                reply = modelApi.chat(new ArrayList<>(messages), tools, budgetExhausted ? ToolChoice.NONE : ToolChoice.AUTO, req.cancelled());
            } catch (CancelledException e) {
                return new RunResult("Cancelled.", usage, toolCallCount, exchange);
            }
            usage = usage.plus(reply.usage());
            if (req.onTurnUsage() != null) {
                req.onTurnUsage().accept(reply.usage());
            }
            messages.add(reply.message());
            exchange.add(reply.message());

            List<ToolCall> toolCalls = reply.message().toolCalls() != null ? reply.message().toolCalls() : List.of();
            if (toolCalls.isEmpty() || budgetExhausted) {
                String replyText = reply.message().textOfContent().trim();
                String finalText = !replyText.isEmpty() ? replyText : "(no reply)";
                return new RunResult(finalText, usage, toolCallCount, exchange);
            }

            List<ImageEntry> imagesForThisTurn = new ArrayList<>();

            for (ToolCall call : toolCalls) {
                if (req.cancelled().getAsBoolean()) {
                    return new RunResult("Cancelled.", usage, toolCallCount, exchange);
                }

                JsonObject args = new JsonObject();
                String parseError = null;
                String rawArgs = call.arguments();
                try {
                    if (rawArgs != null && !rawArgs.trim().isEmpty()) {
                        JsonElement parsed = JsonParser.parseString(rawArgs);
                        if (parsed.isJsonObject()) {
                            args = parsed.getAsJsonObject();
                        } else {
                            parseError = "invalid tool call arguments JSON: arguments must be a JSON object";
                        }
                    }
                } catch (JsonSyntaxException e) {
                    parseError = "invalid tool call arguments JSON: " + e.getMessage();
                }

                String shortArgs = shortArgsOf(args);
                req.onProgress().accept(shortArgs.isEmpty() ? "> " + call.functionName() : "> " + call.functionName() + " " + shortArgs);

                callIndex++;
                ToolCallOutcome result = parseError != null
                        ? new ToolCallOutcome(parseError, List.of(), true)
                        : callTool(call.functionName(), args, req.player(), callIndex, req.cancelled());

                toolCallCount++;

                ChatMessage toolMessage = ChatMessage.tool(call.id(), !result.text().isEmpty() ? result.text() : "(no text)");
                messages.add(toolMessage);
                exchange.add(toolMessage);

                if (!result.images().isEmpty()) {
                    imagesForThisTurn.add(new ImageEntry(call.functionName(), call.id(), result.images()));
                }
            }

            for (ImageEntry entry : imagesForThisTurn) {
                List<ContentPart> parts = new ArrayList<>();
                parts.add(ContentPart.text("Image(s) returned by " + entry.toolName() + " (tool call " + entry.toolCallId() + "):"));
                for (ContentBlock.Image image : entry.images()) {
                    parts.add(ContentPart.imageUrl("data:" + image.mimeType() + ";base64," + image.data(), imageDetail));
                }
                ChatMessage imageMessage = ChatMessage.userParts(parts);
                messages.add(imageMessage);
                exchange.add(imageMessage);
            }

            if (toolCallCount >= maxToolCalls) {
                budgetExhausted = true;
            }
        }
    }

    private List<ToolDef> buildToolDefs() {
        List<ToolDef> defs = new ArrayList<>();
        for (ToolSpec spec : toolRegistry.catalog()) {
            if (allowedToolNames.contains(spec.name())) {
                defs.add(ToolDef.from(spec));
            }
        }
        return defs;
    }

    private ToolCallOutcome callTool(String name, JsonObject args, PlayerInfo player, int callIndex, BooleanSupplier cancelled) {
        if (!allowedToolNames.contains(name)) {
            return new ToolCallOutcome(unknownToolText(name), List.of(), true);
        }
        Optional<Tool> tool = toolRegistry.find(name);
        if (tool.isEmpty()) {
            return new ToolCallOutcome(unknownToolText(name), List.of(), true);
        }

        InvocationContext ctx = InvocationContext.of(
                new InvocationContext.Principal(InvocationContext.Kind.PLAYER, player.uuid(), player.name()),
                "agent-" + callIndex,
                (done, total) -> { });

        ToolResult result = callToolBlocking(tool.get(), ctx, args, cancelled);

        StringBuilder text = new StringBuilder();
        List<ContentBlock.Image> images = new ArrayList<>();
        for (ContentBlock block : result.content()) {
            if (block instanceof ContentBlock.Text t) {
                if (!text.isEmpty()) {
                    text.append("\n");
                }
                text.append(t.text());
            } else if (block instanceof ContentBlock.Image image) {
                images.add(image);
            }
        }
        return new ToolCallOutcome(text.toString(), images, result.isError());
    }

    private String unknownToolText(String name) {
        return "Unknown tool \"" + name + "\". Valid tools: " + String.join(", ", allowedToolNames) + ".";
    }

    private String joinInts(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static String shortArgsOf(JsonObject args) {
        if (args == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String key : SHORT_ARG_KEYS) {
            if (args.has(key) && !args.get(key).isJsonNull()) {
                parts.add(key + "=" + args.get(key).toString());
            }
        }
        return String.join(" ", parts);
    }

    /**
     * Blocks until {@code tool}'s call completes, polling {@code cancelled} so a flipped flag is
     * mirrored onto {@code ctx} (so a running fill can stop at the next slice - the executor
     * already honours it) rather than forcing the future to complete early.
     */
    private ToolResult callToolBlocking(Tool tool, InvocationContext ctx, JsonObject args, BooleanSupplier cancelled) {
        CompletableFuture<ToolResult> future = tool.call(ctx, args);
        while (true) {
            try {
                return future.get(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (cancelled.getAsBoolean()) {
                    ctx.cancel();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(cause);
            }
        }
    }
}
