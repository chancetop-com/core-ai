package ai.core.cli.hub.plugin;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author stephen
 */
class PluginHubClientTest {
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsTheRegistryViewAndDownloadsThePinnedPackage() throws IOException {
        var packageBytes = "package-bytes".getBytes(StandardCharsets.UTF_8);
        var digest = PluginHubClient.sha256(packageBytes);
        server.createContext("/api/plugins/demo", exchange -> respond(exchange, 200,
                registryJson(digest, packageBytes.length)));
        server.createContext("/pkg.zip", exchange -> respond(exchange, 200, new String(packageBytes, StandardCharsets.UTF_8)));

        var client = new PluginHubClient(baseUrl, "key", false);
        var plugin = client.get("demo");

        assertEquals("demo", plugin.id);
        assertEquals("demo-plugin", plugin.name);
        assertEquals(baseUrl + "/pkg.zip", plugin.downloadUrl);
        assertEquals(digest, plugin.sha256);
        assertArrayEquals(packageBytes, client.download(plugin));
    }

    @Test
    void rejectsAPackageWithTheWrongDigest() throws IOException {
        server.createContext("/api/plugins/demo", exchange -> respond(exchange, 200,
                registryJson("0".repeat(64), 13)));
        server.createContext("/pkg.zip", exchange -> respond(exchange, 200, "package-bytes"));

        var client = new PluginHubClient(baseUrl, "key", false);
        var plugin = client.get("demo");
        assertThrows(IllegalStateException.class, () -> client.download(plugin));
    }

    @Test
    void reportsAMissingPluginAsNotFound() {
        server.createContext("/api/plugins", exchange -> respond(exchange, 404, "{\"message\":\"not found\"}"));
        var client = new PluginHubClient(baseUrl, "key", false);
        var error = assertThrows(ai.core.cli.http.RemoteApiException.class, () -> client.get("missing"));
        assertEquals(404, error.statusCode);
    }

    private String registryJson(String digest, int size) {
        return """
                {"id":"demo","name":"demo-plugin","version":"1.0.0","download_url":"%s/pkg.zip",
                 "sha256":"%s","size":%d,"manifest":"{}","required_capabilities":[],"optional_capabilities":[],
                 "components":[]}
                """.formatted(baseUrl, digest, size);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
