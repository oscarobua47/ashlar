// SPDX-License-Identifier: AGPL-3.0-or-later
package net.rcwalter.ashlar.tool;

import net.rcwalter.ashlar.rpc.RpcError;
import net.rcwalter.ashlar.tool.text.ErrorText;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pure-Java port of {@code mcp-server/src/tools/helpers.ts}'s {@code runTool}/{@code
 * runToolContent} (plan.md Step 7.2b): runs a tool body, converting any thrown error into an
 * {@code isError:true} {@link ToolResult}: a {@link RpcError} is formatted with {@link ErrorText}
 * (the same wording {@code formatPluginError} produced; where the TypeScript version printed the
 * plugin URL, this prints {@code "the server"} - there is no separate plugin process to name from
 * inside the JVM), anything else (including a {@link ToolArgError}) falls back to its message.
 * Every {@code Mc*} tool's {@code call} is a thin wrapper around one of these two methods.
 *
 * <p>Also logs one line per call ({@code "[tool] <name>: <ms> ms, <sizes>"}), mirroring {@code
 * logUsage}'s format loosely - the OBS overlay reads the Node process's log, not this one, so an
 * exact match is not required (plan.md Step 7.2b).
 */
public final class ToolRunner {

    private static final Logger LOGGER = Logger.getLogger("Ashlar");

    private ToolRunner() {
    }

    @FunctionalInterface
    public interface TextBody {
        CompletableFuture<String> run();
    }

    @FunctionalInterface
    public interface ContentBody {
        CompletableFuture<List<ContentBlock>> run();
    }

    /** Runs a tool body that produces a single text block, mirroring {@code runTool}. */
    public static CompletableFuture<ToolResult> runText(String name, TextBody body) {
        long startedAt = System.nanoTime();
        try {
            return body.run()
                    .<ToolResult>thenApply(text -> {
                        logUsage(name, startedAt, List.of(ContentBlock.text(text)));
                        return ToolResult.text(text);
                    })
                    .exceptionally(t -> toErrorResult(name, startedAt, t));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(toErrorResult(name, startedAt, e));
        }
    }

    /** Runs a tool body that produces an arbitrary content-block list, mirroring {@code runToolContent}. */
    public static CompletableFuture<ToolResult> runContent(String name, ContentBody body) {
        long startedAt = System.nanoTime();
        try {
            return body.run()
                    .<ToolResult>thenApply(content -> {
                        logUsage(name, startedAt, content);
                        return ToolResult.content(content);
                    })
                    .exceptionally(t -> toErrorResult(name, startedAt, t));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(toErrorResult(name, startedAt, e));
        }
    }

    private static ToolResult toErrorResult(String name, long startedAtNanos, Throwable t) {
        Throwable cause = (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
        String message;
        if (cause instanceof RpcError rpcError) {
            message = ErrorText.formatPluginError(rpcError.code().name(), rpcError.getMessage(), "the server");
        } else {
            message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
        }
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
        String logMessage = message;
        LOGGER.log(Level.FINE, () -> "[tool] " + name + ": " + elapsedMs + " ms, error: " + logMessage);
        return ToolResult.error(message);
    }

    private static void logUsage(String name, long startedAtNanos, List<ContentBlock> content) {
        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000;
        StringBuilder parts = new StringBuilder();
        long tokens = 0;
        for (ContentBlock block : content) {
            if (!parts.isEmpty()) {
                parts.append(" + ");
            }
            if (block instanceof ContentBlock.Text text) {
                long t = (text.text().length() + 3) / 4;
                tokens += t;
                parts.append(text.text().length()).append(" chars (~").append(t).append(" tokens)");
            } else if (block instanceof ContentBlock.Image image) {
                int[] dims = pngDimensions(image.data());
                long t = dims != null ? ((long) dims[0] * dims[1]) / 750 : 0;
                tokens += t;
                parts.append(dims != null ? "image " + dims[0] + "x" + dims[1] + " (~" + t + " tokens)" : "image (size unknown)");
            }
        }
        String partsText = parts.toString();
        long totalTokens = tokens;
        LOGGER.info(() -> "[tool] " + name + ": " + elapsedMs + " ms, " + partsText + " = ~" + totalTokens + " tokens");
    }

    /** Reads width/height from a base64 PNG's IHDR chunk without decoding the image. */
    private static int[] pngDimensions(String base64) {
        try {
            byte[] head = java.util.Base64.getDecoder().decode(base64.substring(0, Math.min(base64.length(), 64)));
            if (head.length < 24) {
                return null;
            }
            int ihdr = ((head[12] & 0xFF) << 24) | ((head[13] & 0xFF) << 16) | ((head[14] & 0xFF) << 8) | (head[15] & 0xFF);
            if (ihdr != 0x49484452) {
                return null;
            }
            int w = ((head[16] & 0xFF) << 24) | ((head[17] & 0xFF) << 16) | ((head[18] & 0xFF) << 8) | (head[19] & 0xFF);
            int h = ((head[20] & 0xFF) << 24) | ((head[21] & 0xFF) << 16) | ((head[22] & 0xFF) << 8) | (head[23] & 0xFF);
            return new int[]{w, h};
        } catch (RuntimeException e) {
            return null;
        }
    }
}
