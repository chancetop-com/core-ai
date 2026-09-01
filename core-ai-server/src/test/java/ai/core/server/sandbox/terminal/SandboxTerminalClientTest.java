package ai.core.server.sandbox.terminal;

import com.sun.net.httpserver.HttpServer;
import core.framework.json.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxTerminalClientTest {
    private HttpServer server;
    private int port;
    private SandboxTerminalClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();
        client = new SandboxTerminalClient("127.0.0.1", port);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createReturnsParsedResultAndSendsExpectedRequestBody() throws Exception {
        var receivedBody = new AtomicReference<String>();
        server.createContext("/terminal", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var body = "{\"terminal_id\":\"term-1\",\"recovered\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        var result = client.create("client-1", 24, 80);

        assertEquals("term-1", result.terminalId());
        assertTrue(result.recovered());
        var sentJson = parseJsonObject(receivedBody.get());
        assertEquals("client-1", sentJson.get("client_id"));
        assertEquals(24.0, ((Number) sentJson.get("rows")).doubleValue());
        assertEquals(80.0, ((Number) sentJson.get("cols")).doubleValue());
    }

    @Test
    void createOn429ThrowsTerminalBusyException() throws Exception {
        server.createContext("/terminal", exchange -> {
            // runtime uses http.Error which sets text/plain even for a JSON-looking body
            var body = "{\"error\":\"terminal busy\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        assertThrows(TerminalBusyException.class, () -> client.create("client-1", 24, 80));
    }

    @Test
    void inputOn410ThrowsTerminalGoneException() throws Exception {
        server.createContext("/terminal/term-1/input", exchange -> {
            // runtime's input error responses may have no body at all
            exchange.sendResponseHeaders(410, -1);
            exchange.close();
        });

        assertThrows(TerminalGoneException.class, () -> client.input("term-1", "ZWNobyBoaQo="));
    }

    @Test
    void inputOn404ThrowsTerminalGoneException() throws Exception {
        server.createContext("/terminal/unknown/input", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        assertThrows(TerminalGoneException.class, () -> client.input("unknown", "ZWNobyBoaQo="));
    }

    @Test
    void resizeSendsBodyAndAcceptsNoContent() throws Exception {
        var receivedBody = new AtomicReference<String>();
        var receivedMethod = new AtomicReference<String>();
        var receivedPath = new AtomicReference<String>();
        server.createContext("/terminal/term-1/size", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        client.resize("term-1", 30, 100);

        assertEquals("PUT", receivedMethod.get());
        assertEquals("/terminal/term-1/size", receivedPath.get());
        var sentJson = parseJsonObject(receivedBody.get());
        assertEquals(30.0, ((Number) sentJson.get("rows")).doubleValue());
        assertEquals(100.0, ((Number) sentJson.get("cols")).doubleValue());
    }

    @Test
    void inputAcceptsNoContent() throws Exception {
        var receivedBody = new AtomicReference<String>();
        var receivedMethod = new AtomicReference<String>();
        var receivedPath = new AtomicReference<String>();
        server.createContext("/terminal/term-1/input", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        client.input("term-1", "ZWNobyBoaQo=");

        assertEquals("POST", receivedMethod.get());
        assertEquals("/terminal/term-1/input", receivedPath.get());
        var sentJson = parseJsonObject(receivedBody.get());
        assertEquals("ZWNobyBoaQo=", sentJson.get("data_base64"));
    }

    @Test
    void closeAcceptsNoContent() throws Exception {
        var receivedMethod = new AtomicReference<String>();
        var receivedPath = new AtomicReference<String>();
        server.createContext("/terminal/term-1", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedPath.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        client.close("term-1");

        assertEquals("DELETE", receivedMethod.get());
        assertEquals("/terminal/term-1", receivedPath.get());
    }

    @Test
    void connectRefusedThrowsTerminalRuntimeUnavailableException() {
        server.stop(0);

        assertThrows(TerminalRuntimeUnavailableException.class, () -> client.create("client-1", 24, 80));
    }

    @Test
    void healthReturnsTrueOn200() {
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        assertTrue(client.health());
    }

    @Test
    void healthReturnsFalseWhenRuntimeUnreachable() {
        server.stop(0);

        assertFalse(client.health());
    }

    @Test
    void eventsUrlBuildsExpectedSseUrl() {
        assertEquals("http://127.0.0.1:" + port + "/terminal/term-1/events", client.eventsUrl("term-1"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String json) {
        return JSON.fromJSON(Map.class, json);
    }
}
