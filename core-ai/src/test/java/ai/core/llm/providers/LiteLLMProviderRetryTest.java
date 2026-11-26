package ai.core.llm.providers;

import ai.core.agent.CancellationException;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.streaming.DefaultStreamingCallback;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import core.framework.http.HTTPClientException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class LiteLLMProviderRetryTest {
    private static CompletionRequest request() {
        return CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), List.of(), 0.0, "test-model", "test");
    }

    private static void statusOnly(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void sse(HttpExchange exchange, String... events) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (var event : events) {
                out.write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        }
    }

    private static void truncatedEvent(HttpExchange exchange, String partial) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(("data: " + partial).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static String gunzip(byte[] body) {
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void retriesWhileCoreAiServerRestarts() {
        var provider = provider((exchange, attempt) -> {
            if (attempt == 1) {
                statusOnly(exchange, 503);
            } else {
                sse(exchange,
                        "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}",
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                        "[DONE]");
            }
        }, 5);

        var response = provider.completionStream(request(), new DefaultStreamingCallback());

        assertEquals("hello", response.choices.getFirst().message.content);
        assertEquals(2, requests.get());
    }

    @Test
    void retriesEmptyStreamBeforeGivingUp() {
        var provider = provider((exchange, attempt) -> {
            if (attempt == 1) {
                sse(exchange);
            } else {
                sse(exchange,
                        "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"recovered\"}}]}",
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                        "[DONE]");
            }
        }, 5);

        var response = provider.completionStream(request(), new DefaultStreamingCallback());

        assertEquals("recovered", response.choices.getFirst().message.content);
        assertEquals(2, requests.get());
    }

    @Test
    void reportsServerUnavailableAfterTheRetryBudget() {
        var provider = provider((exchange, attempt) -> statusOnly(exchange, 503), 2);

        var error = assertThrows(LLMUnavailableException.class,
                () -> provider.completionStream(request(), new DefaultStreamingCallback()));

        assertTrue(error.getMessage().contains("core-ai-server"), error.getMessage());
        assertTrue(error.getMessage().contains("statusCode=503"), error.getMessage());
        assertTrue(requests.get() >= 2, "must keep retrying within the budget, requests=" + requests.get());
    }

    @Test
    void doesNotRetryAfterPartialOutput() {
        var provider = provider((exchange, attempt) -> sse(exchange,
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"partial\"}}]}"), 10);

        var error = assertThrows(RuntimeException.class,
                () -> provider.completionStream(request(), new DefaultStreamingCallback()));

        assertTrue(error.getMessage().contains("partial output"), error.getMessage());
        assertEquals(1, requests.get(), "a retry after streamed text would duplicate it");
    }

    @Test
    void doesNotRetryRejectedRequest() {
        var provider = provider((exchange, attempt) -> {
            var body = "{\"error\":{\"message\":\"context length exceeded\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }, 10);

        var error = assertThrows(RuntimeException.class,
                () -> provider.completionStream(request(), new DefaultStreamingCallback()));

        assertTrue(error.getMessage().contains("statusCode=400"), error.getMessage());
        assertFalse(error instanceof LLMUnavailableException);
        assertEquals(2, requests.get(), "one SSE attempt plus one body read, never a retry");
    }

    @Test
    void retriesWhenTheConnectionDropsInTheMiddleOfAnEvent() {
        var provider = provider((exchange, attempt) -> {
            if (attempt == 1) {
                truncatedEvent(exchange, "{\"id\":\"chatcmpl-");
            } else {
                sse(exchange,
                        "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"recovered\"}}]}",
                        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                        "[DONE]");
            }
        }, 5);

        var response = provider.completionStream(request(), new DefaultStreamingCallback());

        assertEquals("recovered", response.choices.getFirst().message.content);
        assertEquals(2, requests.get());
    }

    @Test
    void reportsAnInterruptedStreamWhenEveryAttemptIsTruncated() {
        var provider = provider((exchange, attempt) -> truncatedEvent(exchange, "{\"id\":\"chatcmpl-"), 5);

        var error = assertThrows(LLMUnavailableException.class,
                () -> provider.completionStream(request(), new DefaultStreamingCallback()));

        assertTrue(error.getMessage().contains("closed the connection mid-event"), error.getMessage());
        assertFalse(error.getMessage().contains("JsonEOFException"), error.getMessage());
    }

    @Test
    void reportsAnInterruptedStreamAfterPartialOutput() {
        var provider = provider((exchange, attempt) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                           + "data: {\"choices\":[{\"delta\":").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }, 10);

        var error = assertThrows(RuntimeException.class,
                () -> provider.completionStream(request(), new DefaultStreamingCallback()));

        assertTrue(error.getMessage().contains("connection lost after partial output"), error.getMessage());
        assertTrue(error.getMessage().contains("mid-event"), error.getMessage());
        assertEquals(1, requests.get(), "a retry after streamed text would duplicate it");
    }

    @Test
    void classifiesTransportAndGatewayFailures() {
        assertTrue(LLMRetrySupport.retryable(new HTTPClientException(
                "sse request failed, uri=http://localhost/chat/completions, error=Connection refused", "HTTP_REQUEST_FAILED")));
        assertTrue(LLMRetrySupport.retryable(new HTTPClientException(
                "invalid sse response, statusCode=503, content-type=text/html", "HTTP_REQUEST_FAILED")));
        assertTrue(LLMRetrySupport.retryable(new HTTPClientException(
                "failed to read sse response, error=unexpected end of stream", "HTTP_REQUEST_FAILED")));
        assertTrue(LLMRetrySupport.retryable(new RuntimeException(new SocketTimeoutException("read timed out"))));
        assertFalse(LLMRetrySupport.retryable(new HTTPClientException(
                "invalid sse response, statusCode=400, content-type=application/json", "HTTP_REQUEST_FAILED")));
        assertFalse(LLMRetrySupport.retryable(new HTTPClientException(
                "invalid sse response, statusCode=413, content-type=text/html", "HTTP_REQUEST_FAILED")));
        assertFalse(LLMRetrySupport.retryable(new HTTPClientException("uri is invalid, uri=::bad", "INVALID_URL")));
        assertFalse(LLMRetrySupport.retryable(new LLMStreamException("invalid request")));
        assertFalse(LLMRetrySupport.retryable(new CancellationException()));
    }

    @Test
    void keepsTheProviderBodyForContextWindowRejections() {
        var provider = provider((exchange, attempt) -> {
            var body = "{\"error\":{\"message\":\"This model's maximum context length is 1048576 tokens. "
                    + "However, you requested 1200000 tokens\",\"type\":\"invalid_request_error\"}}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }, 5);

        var error = assertThrows(LLMContextWindowExceededException.class,
                () -> provider.completionStream(request(), new DefaultStreamingCallback()));

        assertTrue(error.getMessage().contains("maximum context length"), error.getMessage());
        assertEquals(2, requests.get(), "one rejected SSE attempt plus one body read");
    }

    @Test
    void backsOffExponentiallyAndCapsTheDelay() {
        assertEquals(1000, LLMRetrySupport.retryDelayMillis(1, 0));
        assertEquals(2000, LLMRetrySupport.retryDelayMillis(2, 0));
        assertEquals(4000, LLMRetrySupport.retryDelayMillis(3, 0));
        assertEquals(8000, LLMRetrySupport.retryDelayMillis(4, 0));
        assertEquals(10000, LLMRetrySupport.retryDelayMillis(5, 0));
        assertEquals(10000, LLMRetrySupport.retryDelayMillis(20, 0));
        assertEquals(1200, LLMRetrySupport.retryDelayMillis(1, 0.2));
    }

    @Test
    void gzipsLargeRequestsToTheCoreAiServerProxy() {
        var encoding = new AtomicReference<String>();
        var received = new AtomicReference<byte[]>();
        var provider = provider((exchange, attempt) -> {
            encoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
            try (var in = exchange.getRequestBody()) {
                received.set(in.readAllBytes());
            }
            sse(exchange,
                    "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}",
                    "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                    "[DONE]");
        }, 5);
        var content = "large-message ".repeat(500);
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, content)), List.of(), 0.0, "test-model", "test");

        provider.completionStream(request, new DefaultStreamingCallback());

        assertEquals("gzip", encoding.get());
        assertTrue(gunzip(received.get()).contains(content));
    }

    @Test
    void detectsTheCoreAiServerProxyEndpoint() {
        assertTrue(LLMRetrySupport.isServerProxyEndpoint("https://uat.example.com/api/cli/v1/chat/completions"));
        assertTrue(LLMRetrySupport.isServerProxyEndpoint("https://uat.example.com/api/litellm/v1/chat/completions"));
        assertFalse(LLMRetrySupport.isServerProxyEndpoint("https://api.deepseek.com/v1/chat/completions"));
        assertFalse(LLMRetrySupport.isServerProxyEndpoint(null));
    }

    private LiteLLMProvider provider(Attempt attempt, long retrySeconds) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/api/cli/v1/chat/completions", exchange -> {
                int current = requests.incrementAndGet();
                attempt.handle(exchange, current);
            });
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start test server", e);
        }
        var config = new LLMProviderConfig("test-model", 0.7, null);
        config.setServerRetrySeconds(retrySeconds);
        var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/cli/v1";
        return new LiteLLMProvider(config, url, "test-key");
    }

    @FunctionalInterface
    private interface Attempt {
        void handle(HttpExchange exchange, int attempt) throws IOException;
    }
}
