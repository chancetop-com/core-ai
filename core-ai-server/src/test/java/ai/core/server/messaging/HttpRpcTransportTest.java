package ai.core.server.messaging;

import ai.core.utils.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class HttpRpcTransportTest {
    @Test
    void sendsRpcToTheRegisteredPod() throws Exception {
        var seenToken = new AtomicReference<String>();
        var seenTarget = new AtomicReference<String>();
        var seenBody = new AtomicReference<String>();
        var server = startServer(exchange -> {
            seenToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            seenTarget.set(exchange.getRequestHeaders().getFirst("X-Target-Pod"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, okPayload("{\"value\":\"done\"}"));
        });
        try {
            var transport = new HttpRpcTransport(pod -> "127.0.0.1:" + server.getAddress().getPort(), "secret");
            var json = transport.call("pod-1", command(), Duration.ofSeconds(2));

            assertEquals("done", RpcClient.parseResponse(json, TestResponse.class).value);
            assertEquals("secret", seenToken.get());
            assertEquals("pod-1", seenTarget.get());
            var sent = JsonUtil.fromJson(InternalRpcCommand.class, seenBody.get());
            assertEquals(CommandType.LOAD_SKILLS.name(), sent.type);
            assertEquals("request-1", sent.requestId);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonIpAddressIsNotDelivered() {
        var transport = new HttpRpcTransport(pod -> "core-ai-server-pod:8080", "secret");

        assertThrows(RpcNotDeliveredException.class,
            () -> transport.call("pod-1", command(), Duration.ofSeconds(2)));
    }

    @Test
    void missingAddressIsNotDelivered() {
        var transport = new HttpRpcTransport(pod -> null, "secret");

        assertThrows(RpcNotDeliveredException.class,
            () -> transport.call("pod-1", command(), Duration.ofSeconds(2)));
    }

    @Test
    void connectionRefusedIsNotDelivered() {
        var transport = new HttpRpcTransport(pod -> "127.0.0.1:1", "secret");

        assertThrows(RpcNotDeliveredException.class,
            () -> transport.call("pod-1", command(), Duration.ofSeconds(2)));
    }

    @Test
    void missingEndpointIsNotDelivered() throws Exception {
        var server = startServer(exchange -> respond(exchange, 404, "{}"));
        try {
            var transport = new HttpRpcTransport(pod -> "127.0.0.1:" + server.getAddress().getPort(), "secret");

            assertThrows(RpcNotDeliveredException.class,
                () -> transport.call("pod-1", command(), Duration.ofSeconds(2)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unauthorizedIsAnErrorNotFallback() throws Exception {
        var server = startServer(exchange -> respond(exchange, 401, "{}"));
        try {
            var transport = new HttpRpcTransport(pod -> "127.0.0.1:" + server.getAddress().getPort(), "secret");

            var error = assertThrows(RuntimeException.class,
                () -> transport.call("pod-1", command(), Duration.ofSeconds(2)));
            assertTrue(error.getMessage().contains("status=401"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readTimeoutIsAnErrorNotFallback() throws Exception {
        var server = startServer(exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, okPayload("{}"));
        });
        try {
            var transport = new HttpRpcTransport(pod -> "127.0.0.1:" + server.getAddress().getPort(), "secret");

            assertThrows(RuntimeException.class,
                () -> transport.call("pod-1", command(), Duration.ofMillis(200)));
        } finally {
            server.stop(0);
        }
    }

    private SessionCommand command() {
        return new SessionCommand(CommandType.LOAD_SKILLS, "session-1", "user-1", "{}", "request-1");
    }

    private HttpServer startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_internal/rpc", handler);
        server.start();
        return server;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String okPayload(String payloadJson) {
        return JsonUtil.toJson(Map.of("ok", Boolean.TRUE, "payload", payloadJson));
    }

    public static class TestResponse {
        public String value;
    }
}

