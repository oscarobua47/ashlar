// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cc.wujm.ashlar.agent.model.ChatMessage;
import cc.wujm.ashlar.agent.model.ToolCall;
import cc.wujm.ashlar.agent.model.ToolDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Port of {@code mcp-server/src/agent/provider.test.ts}. */
class ModelClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange, int count) throws IOException;
    }

    private String startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger count = new AtomicInteger(0);
        server.createContext("/", exchange -> {
            int c = count.incrementAndGet();
            try {
                handler.handle(exchange, c);
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(null);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void readBody(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void respondText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "text/plain");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static ModelConfig cfg(String baseUrl) {
        return new ModelConfig(baseUrl, "test-key", "test-model", Duration.ofSeconds(5));
    }

    private static ModelClient client(String baseUrl) {
        return new ModelClient(cfg(baseUrl), HttpClient.newHttpClient());
    }

    @Test
    void toolCallsReplyThenFinalTextReply() throws IOException {
        String url = startServer((exchange, count) -> {
            readBody(exchange);
            if (count == 1) {
                respondJson(exchange, 200, """
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call-1","type":"function","function":{"name":"mc_status","arguments":"{}"}}]},
                        "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}
                        """);
            } else {
                respondJson(exchange, 200, """
                        {"choices":[{"message":{"role":"assistant","content":"Done."},"finish_reason":"stop"}],
                        "usage":{"prompt_tokens":20,"completion_tokens":3}}
                        """);
            }
        });
        ModelClient c = client(url);

        Reply first = c.chat(List.of(ChatMessage.user("hi")), List.of(), ToolChoice.AUTO, () -> false);
        List<ToolCall> toolCalls = first.message().toolCalls();
        assertEquals(1, toolCalls.size());
        assertEquals("mc_status", toolCalls.get(0).functionName());
        assertEquals("tool_calls", first.finishReason());

        Reply second = c.chat(List.of(ChatMessage.user("hi")), List.of(), ToolChoice.AUTO, () -> false);
        assertEquals("Done.", second.message().contentText());
        assertEquals("stop", second.finishReason());
        assertEquals(20, second.usage().inputTokens());
        assertEquals(0, second.usage().cachedInputTokens());
        assertEquals(3, second.usage().outputTokens());
    }

    @Test
    void normalisesDeepSeekPromptCacheHitTokensField() throws IOException {
        String url = startServer((exchange, count) -> {
            readBody(exchange);
            respondJson(exchange, 200, """
                    {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":100,"prompt_cache_hit_tokens":60,"prompt_cache_miss_tokens":40,"completion_tokens":10}}
                    """);
        });
        Reply result = client(url).chat(List.of(ChatMessage.user("hi")), List.of(), ToolChoice.AUTO, () -> false);
        assertEquals(60, result.usage().cachedInputTokens());
        assertEquals(40, result.usage().inputTokens());
        assertEquals(10, result.usage().outputTokens());
    }

    @Test
    void normalisesOpenAiStylePromptTokensDetailsCachedTokensField() throws IOException {
        String url = startServer((exchange, count) -> {
            readBody(exchange);
            respondJson(exchange, 200, """
                    {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":25},"completion_tokens":10}}
                    """);
        });
        Reply result = client(url).chat(List.of(ChatMessage.user("hi")), List.of(), ToolChoice.AUTO, () -> false);
        assertEquals(25, result.usage().cachedInputTokens());
        assertEquals(75, result.usage().inputTokens());
        assertEquals(10, result.usage().outputTokens());
    }

    @Test
    void missingCacheFieldsTreatedAsZeroCached() throws IOException {
        String url = startServer((exchange, count) -> {
            readBody(exchange);
            respondJson(exchange, 200, """
                    {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":50,"completion_tokens":5}}
                    """);
        });
        Reply result = client(url).chat(List.of(ChatMessage.user("hi")), List.of(), ToolChoice.AUTO, () -> false);
        assertEquals(0, result.usage().cachedInputTokens());
        assertEquals(50, result.usage().inputTokens());
        assertEquals(5, result.usage().outputTokens());
    }

    @Test
    void a500ThenA200SucceedsViaRetry() throws IOException {
        String url = startServer((exchange, count) -> {
            readBody(exchange);
            if (count == 1) {
                respondText(exchange, 500, "server error");
                return;
            }
            respondJson(exchange, 200, """
                    {"choices":[{"message":{"role":"assistant","content":"ok"}}],"usage":{}}
                    """);
        });
        Reply result = client(url).chat(List.of(ChatMessage.user("hi")), List.of(), ToolChoice.AUTO, () -> false);
        assertEquals("ok", result.message().contentText());
    }

    @Test
    void a400ThrowsModelExceptionWithBodyExcerptNoRetry() throws IOException {
        AtomicInteger requestCount = new AtomicInteger(0);
        String url = startServer((exchange, count) -> {
            requestCount.incrementAndGet();
            readBody(exchange);
            respondText(exchange, 400, "bad request: invalid model name given");
        });
        ModelClient c = client(url);
        ModelException ex = assertThrows(ModelException.class,
                () -> c.chat(List.of(ChatMessage.user("hi")), List.of(), ToolChoice.AUTO, () -> false));
        assertEquals(400, ex.status());
        assertTrue(ex.getMessage().contains("bad request: invalid model name given"));
        assertEquals(1, requestCount.get(), "a 400 must not be retried");
    }

    @Test
    void aCancelledRequestRejectsPromptly() throws IOException {
        CountDownLatch neverResponds = new CountDownLatch(1);
        String url = startServer((exchange, count) -> {
            try {
                neverResponds.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ModelClient c = client(url);
        AtomicBoolean cancelled = new AtomicBoolean(true);

        long startedAt = System.currentTimeMillis();
        assertThrows(CancelledException.class,
                () -> c.chat(List.of(ChatMessage.user("hi")), List.of(), ToolChoice.AUTO, cancelled::get));
        long elapsedMs = System.currentTimeMillis() - startedAt;
        assertTrue(elapsedMs < 2000, "abort should reject promptly, took " + elapsedMs + "ms");
        neverResponds.countDown();
    }

    @Test
    void omitsToolsFieldFromRequestBodyWhenToolsEmpty() throws IOException {
        AtomicBoolean sawTools = new AtomicBoolean(true);
        String url = startServer((exchange, count) -> {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject body = JsonParser.parseString(raw).getAsJsonObject();
            sawTools.set(body.has("tools"));
            respondJson(exchange, 200, """
                    {"choices":[{"message":{"role":"assistant","content":"ok"}}],"usage":{}}
                    """);
        });
        client(url).chat(List.of(ChatMessage.user("hi")), List.<ToolDef>of(), ToolChoice.AUTO, () -> false);
        assertFalse(sawTools.get());
    }
}
