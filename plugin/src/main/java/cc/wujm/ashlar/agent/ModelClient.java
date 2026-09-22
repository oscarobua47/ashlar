// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cc.wujm.ashlar.agent.model.ChatMessage;
import cc.wujm.ashlar.agent.model.ToolDef;
import cc.wujm.ashlar.agent.model.Usage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Calls the configured OpenAI-compatible {@code /chat/completions} endpoint (pure-Java port of
 * {@code mcp-server/src/agent/provider.ts}'s {@code chatCompletion}). Retries on 429/5xx up to 3
 * times with 1s/2s/4s backoff (honouring {@code Retry-After} when present); any other non-2xx
 * throws {@link ModelException} with the status and the first 300 characters of the response
 * body. A response is awaited by polling a short interval so that {@code cancelled} turning true
 * (checked between attempts, and while waiting for a response) rejects promptly via {@link
 * CancelledException} rather than waiting for the network.
 */
public final class ModelClient implements ModelApi {

    private static final int[] BACKOFF_MS = {1000, 2000, 4000};
    private static final long POLL_MS = 50;

    // Not final: agent.model.* is hot (step8j-prompt.md) - AgentService#applyConfig calls
    // updateConfig; chat() reads this field once at the top of each call, so a request already in
    // flight keeps the ModelConfig it started with.
    private volatile ModelConfig cfg;
    private final HttpClient http;
    private final Logger logger;

    public ModelClient(ModelConfig cfg, HttpClient http) {
        this(cfg, http, Logger.getLogger("Ashlar"));
    }

    public ModelClient(ModelConfig cfg, HttpClient http, Logger logger) {
        this.cfg = cfg;
        this.http = http;
        this.logger = logger;
    }

    /** Applies a new {@link ModelConfig} ({@code /ashlar reload}, step8j-prompt.md); a call already in progress keeps the config it started with. */
    public void updateConfig(ModelConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public Reply chat(List<ChatMessage> messages, List<ToolDef> tools, ToolChoice toolChoice, BooleanSupplier cancelled) {
        ModelConfig cfg = this.cfg; // one snapshot for the whole call, including its retries: a reload mid-call must not mix old/new settings
        String url = cfg.baseUrl() + "/chat/completions";
        String payload = buildRequestBody(cfg, messages, tools, toolChoice);

        for (int attempt = 0; attempt <= BACKOFF_MS.length; attempt++) {
            if (cancelled.getAsBoolean()) {
                throw new CancelledException();
            }

            long startedAt = System.currentTimeMillis();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(cfg.requestTimeout())
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = sendWithCancellation(request, cancelled);
            int status = response.statusCode();

            if ((status == 429 || status >= 500) && attempt < BACKOFF_MS.length) {
                Long retryAfterMs = parseRetryAfterMs(response.headers().firstValue("retry-after").orElse(null));
                sleepOrCancel(retryAfterMs != null ? retryAfterMs : BACKOFF_MS[attempt], cancelled);
                continue;
            }

            if (status < 200 || status >= 300) {
                String body = response.body() != null ? response.body() : "";
                throw new ModelException(status, body.substring(0, Math.min(300, body.length())));
            }

            return parseReply(cfg, response.body(), status, startedAt);
        }

        // Unreachable: the loop always returns or throws.
        throw new ModelException(0, "chatCompletion: exhausted retries without a response");
    }

    private static String buildRequestBody(ModelConfig cfg, List<ChatMessage> messages, List<ToolDef> tools, ToolChoice toolChoice) {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model());
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            msgs.add(m.toJson());
        }
        body.add("messages", msgs);
        body.addProperty("stream", false);
        if (!tools.isEmpty()) {
            JsonArray toolsJson = new JsonArray();
            for (ToolDef t : tools) {
                toolsJson.add(t.toJson());
            }
            body.add("tools", toolsJson);
        }
        if (toolChoice == ToolChoice.NONE) {
            body.addProperty("tool_choice", "none");
        }
        return body.toString();
    }

    private Reply parseReply(ModelConfig cfg, String responseBody, int status, long startedAt) {
        JsonObject json;
        try {
            json = JsonParser.parseString(responseBody != null ? responseBody : "{}").getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ModelException(status, "response was not valid JSON: " + e.getMessage());
        }
        JsonArray choices = json.has("choices") && json.get("choices").isJsonArray() ? json.getAsJsonArray("choices") : null;
        if (choices == null || choices.isEmpty()) {
            throw new ModelException(status, "response had no choices[0]");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        ChatMessage message = ChatMessage.fromJson(choice.getAsJsonObject("message"));
        String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                ? choice.get("finish_reason").getAsString() : "";

        JsonObject usageJson = json.has("usage") && json.get("usage").isJsonObject() ? json.getAsJsonObject("usage") : new JsonObject();
        long promptTokens = longField(usageJson, "prompt_tokens", 0);
        long cachedFromDeepseek = longField(usageJson, "prompt_cache_hit_tokens", -1);
        long cachedInputTokens;
        if (cachedFromDeepseek >= 0) {
            cachedInputTokens = cachedFromDeepseek;
        } else {
            JsonObject details = usageJson.has("prompt_tokens_details") && usageJson.get("prompt_tokens_details").isJsonObject()
                    ? usageJson.getAsJsonObject("prompt_tokens_details") : null;
            cachedInputTokens = details != null ? longField(details, "cached_tokens", 0) : 0;
        }
        long outputTokens = longField(usageJson, "completion_tokens", 0);
        Usage usage = new Usage(Math.max(0, promptTokens - cachedInputTokens), cachedInputTokens, outputTokens);

        long elapsedMs = System.currentTimeMillis() - startedAt;
        boolean hasToolCalls = message.toolCalls() != null && !message.toolCalls().isEmpty();
        logger.log(Level.INFO, () -> "[agent-provider] model=" + cfg.model() + " prompt_tokens=" + promptTokens
                + " cached_tokens=" + cachedInputTokens + " completion_tokens=" + outputTokens
                + " elapsed=" + elapsedMs + "ms tool_calls=" + hasToolCalls);

        return new Reply(message, usage, finishReason);
    }

    private static long longField(JsonObject o, String field, long fallback) {
        if (!o.has(field) || o.get(field).isJsonNull()) {
            return fallback;
        }
        return o.get(field).getAsLong();
    }

    /** Awaits the response, polling {@code cancelled} so a flipped flag rejects promptly instead of blocking on the network. */
    private HttpResponse<String> sendWithCancellation(HttpRequest request, BooleanSupplier cancelled) {
        CompletableFuture<HttpResponse<String>> future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        while (true) {
            try {
                return future.get(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (cancelled.getAsBoolean()) {
                    future.cancel(true);
                    throw new CancelledException();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new CancelledException();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof IOException io) {
                    throw new UncheckedIOException(io);
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(cause);
            }
        }
    }

    private void sleepOrCancel(long delayMs, BooleanSupplier cancelled) {
        long remaining = delayMs;
        while (remaining > 0) {
            long step = Math.min(POLL_MS, remaining);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
            remaining -= step;
            if (cancelled.getAsBoolean()) {
                throw new CancelledException();
            }
        }
    }

    /** Parses a {@code Retry-After} header value (seconds, or an HTTP date) into a millisecond delay, or null if unusable. */
    private static Long parseRetryAfterMs(String header) {
        if (header == null) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(header.trim());
            if (seconds >= 0) {
                return (long) (seconds * 1000);
            }
        } catch (NumberFormatException ignored) {
            // fall through to date parsing
        }
        try {
            ZonedDateTime dt = ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            long delta = dt.toInstant().toEpochMilli() - System.currentTimeMillis();
            return delta > 0 ? delta : 0;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
