package ai.core.server.sandbox.kubernetes;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link KubernetesClient#listPvcs(String)} request encoding and JSON deserialization
 * against an in-process HTTP server.
 *
 * @author stephen
 */
class KubernetesClientListPvcsTest {

    @Test
    void listPvcsEncodesSelectorAndDeserializesItems() throws IOException {
        var capturedUri = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/namespaces/test-ns/persistentvolumeclaims", exchange -> {
            capturedUri.set(exchange.getRequestURI().toString());
            var body = """
                    {
                      "items": [
                        {
                          "metadata": {
                            "name": "ws-abc-g0",
                            "creationTimestamp": "2026-11-28T10:00:00Z",
                            "labels": {
                              "core-ai/component": "workspace",
                              "core-ai/workspace-id": "ws-1",
                              "core-ai/volume-generation": "0"
                            }
                          },
                          "status": {
                            "phase": "Bound"
                          }
                        }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var client = new KubernetesClient("http://127.0.0.1:" + server.getAddress().getPort(), "tok", "test-ns", 5);
            var pvcs = client.listPvcs("core-ai/component=workspace");

            assertEquals("/api/v1/namespaces/test-ns/persistentvolumeclaims?labelSelector=core-ai%2Fcomponent%3Dworkspace",
                    capturedUri.get());
            assertEquals(1, pvcs.size());
            assertEquals("ws-abc-g0", pvcs.getFirst().metadata.name);
            assertEquals("2026-11-28T10:00:00Z", pvcs.getFirst().metadata.creationTimestamp);
            assertEquals("ws-1", pvcs.getFirst().metadata.labels.get("core-ai/workspace-id"));
            assertEquals("Bound", pvcs.getFirst().getPhase());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listPvcsThrowsOnNon200Status() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/namespaces/test-ns/persistentvolumeclaims", exchange -> {
            var body = "forbidden".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var client = new KubernetesClient("http://127.0.0.1:" + server.getAddress().getPort(), "tok", "test-ns", 5);
            assertThrows(RuntimeException.class, () -> client.listPvcs("core-ai/component=workspace"));
        } finally {
            server.stop(0);
        }
    }
}
