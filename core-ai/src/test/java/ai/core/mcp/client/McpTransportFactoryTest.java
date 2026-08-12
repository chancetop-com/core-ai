package ai.core.mcp.client;

import ai.core.tool.CallerHeaderProvider;
import ai.core.tool.OutboundCallerContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTransportFactoryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicInteger getRequests = new AtomicInteger();
    private final CountDownLatch getRequestReceived = new CountDownLatch(1);
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> accept = new AtomicReference<>();
    private final AtomicReference<String> managerId = new AtomicReference<>();
    private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
    private boolean returnEmptySseForInitializedNotification;
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ads", this::handleRequest);
        server.createContext("/mcp", this::handleRequest);
        server.start();
    }

    @AfterEach
    void stopServer() {
        CallerHeaderProvider.set(caller -> java.util.Map.of());
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void streamableHttpSupportsPostOnlyServerThatRejectsStandaloneSse() throws Exception {
        var transportFailure = new AtomicReference<Throwable>();
        var transportFailureReceived = new CountDownLatch(1);
        var transport = HttpClientStreamableHttpTransport
            .builder("http://127.0.0.1:" + server.getAddress().getPort())
            .endpoint("/ads")
            .openConnectionOnStartup(true)
            .httpRequestCustomizer((request, method, uri, body, context) ->
                request.header("Authorization", "Bearer test-token"))
            .build();
        transport.setExceptionHandler(error -> {
            transportFailure.set(error);
            transportFailureReceived.countDown();
        });
        try {
            transport.connect(messages -> messages).block(Duration.ofSeconds(2));
            assertTrue(getRequestReceived.await(2, TimeUnit.SECONDS));
            assertFalse(transportFailureReceived.await(1, TimeUnit.SECONDS),
                "HTTP 405 must not fail the optional SSE stream");
        } finally {
            transport.closeGracefully().block(Duration.ofSeconds(2));
        }

        assertNull(handlerFailure.get(), () -> "test server failed: " + handlerFailure.get());
        assertNull(transportFailure.get(), () -> "HTTP 405 must not fail the optional SSE stream: " + transportFailure.get());
        assertTrue(getRequests.get() > 0, "client should probe the optional standalone SSE stream");
        assertEquals("Bearer test-token", authorization.get());
    }

    @Test
    @Timeout(10)
    void factoryInitializesCustomEndpointAndListsToolsWithConfiguredHeaders() {
        var builder = McpServerConfig.http(serverUrl())
            .name("post-only-mcp")
            .endpoint("/ads")
            .bearerToken("test-token");
        useShortTimeouts(builder);

        try (var client = new McpClientService(builder.build())) {
            assertEquals("ping", client.listTools().getFirst().name());
        }

        assertNull(handlerFailure.get(), () -> "test server failed: " + handlerFailure.get());
        assertEquals("Bearer test-token", authorization.get());
    }

    @Test
    @Timeout(10)
    void configuredAcceptHeaderHandlesEmptySseInitializedResponse() {
        returnEmptySseForInitializedNotification = true;
        var builder = McpServerConfig.http(serverUrl())
            .name("empty-sse-notification-mcp")
            .endpoint("/ads")
            .header("Accept", "application/json");
        useShortTimeouts(builder);

        try (var client = new McpClientService(builder.build())) {
            assertEquals("ping", client.listTools().getFirst().name());
        }

        assertNull(handlerFailure.get(), () -> "test server failed: " + handlerFailure.get());
        assertEquals("application/json", accept.get());
    }

    @Test
    @Timeout(10)
    void callerHeadersOverrideConfiguredHeadersCaseInsensitively() {
        CallerHeaderProvider.set(caller -> java.util.Map.of("x-manager-id", "caller-value"));
        var builder = McpServerConfig.http(serverUrl())
            .name("caller-header-mcp")
            .endpoint("/ads")
            .header("X-Manager-Id", "static-value");
        useShortTimeouts(builder);
        var caller = new OutboundCallerContext.Caller("external", "user", "manager", java.util.Map.of());

        var callerScope = OutboundCallerContext.set(caller);
        McpClientService client = null;
        try {
            assertNotNull(callerScope);
            assertEquals(caller, OutboundCallerContext.current());
            client = new McpClientService(builder.build());
            assertEquals("ping", client.listTools().getFirst().name());
        } finally {
            if (client != null) {
                client.close();
            }
            callerScope.close();
        }

        assertEquals("caller-value", managerId.get());
    }

    @Test
    @Timeout(10)
    void factoryDoesNotDuplicateDefaultMcpEndpointFromFullUrl() {
        var builder = McpServerConfig.http(serverUrl() + "/mcp").name("default-mcp");
        useShortTimeouts(builder);

        try (var client = new McpClientService(builder.build())) {
            assertEquals("ping", client.listTools().getFirst().name());
        }

        assertNull(handlerFailure.get(), () -> "test server failed: " + handlerFailure.get());
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            dispatchRequest(exchange);
        }
    }

    private void dispatchRequest(HttpExchange exchange) throws IOException {
        try {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            managerId.set(exchange.getRequestHeaders().getFirst("X-Manager-Id"));
            switch (exchange.getRequestMethod()) {
                case "GET" -> rejectStandaloneSse(exchange);
                case "POST" -> handleJsonRpc(exchange);
                case "DELETE" -> exchange.sendResponseHeaders(200, -1);
                default -> exchange.sendResponseHeaders(405, -1);
            }
        } catch (Throwable error) {
            handlerFailure.set(error);
            if (exchange.getResponseCode() < 0) {
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    private void rejectStandaloneSse(HttpExchange exchange) throws IOException {
        getRequests.incrementAndGet();
        getRequestReceived.countDown();
        writeJson(exchange, 405, "{\"error\":\"GET is not supported; use POST for JSON-RPC\"}");
    }

    private void handleJsonRpc(HttpExchange exchange) throws IOException {
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        if ("initialize".equals(method)) {
            String response = ("{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"post-only-test\",\"version\":\"1.0.0\"}}}")
                .formatted(request.get("id"));
            writeJson(exchange, 200, response);
        } else if ("notifications/initialized".equals(method)) {
            if (returnEmptySseForInitializedNotification
                && !"application/json".equals(exchange.getRequestHeaders().getFirst("Accept"))) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
            } else if (returnEmptySseForInitializedNotification) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
            } else {
                exchange.sendResponseHeaders(202, -1);
            }
        } else if ("tools/list".equals(method)) {
            String response = ("{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"tools\":[{\"name\":\"ping\","
                + "\"description\":\"test tool\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}}")
                .formatted(request.get("id"));
            writeJson(exchange, 200, response);
        } else {
            writeJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":{}}");
        }
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void useShortTimeouts(HttpServerConfigBuilder builder) {
        builder.connectTimeout(Duration.ofSeconds(2));
        builder.requestTimeout(Duration.ofSeconds(2));
        builder.initializationTimeout(Duration.ofSeconds(2));
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
