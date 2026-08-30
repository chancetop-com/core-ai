package ai.core.tool.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class HTTPReferenceImageLoaderTest {
    private HttpServer server;
    private GenerateVideoTool.HTTPReferenceImageLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // mirrors FileResponseSupport: artifact content answers 307 to a pre-signed storage URL
        server.createContext("/api/public/artifacts/token/content", exchange -> {
            exchange.getResponseHeaders().set("Location", "/storage/image.png");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/storage/image.png", exchange -> {
            var bytes = "image-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().set("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/no-location", exchange -> {
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        loader = new GenerateVideoTool.HTTPReferenceImageLoader();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void followsTemporaryRedirectToTheStorageUrl() {
        var loaded = loader.load(baseUrl() + "/api/public/artifacts/token/content");

        assertArrayEquals("image-bytes".getBytes(StandardCharsets.UTF_8), loaded.data());
        assertEquals("image/png", loaded.contentType());
    }

    @Test
    void rejectsRedirectLoops() {
        var error = assertThrows(IllegalArgumentException.class, () -> loader.load(baseUrl() + "/loop"));

        assertTrue(error.getMessage().contains("too many redirects"));
    }

    @Test
    void rejectsRedirectWithoutLocation() {
        var error = assertThrows(IllegalArgumentException.class, () -> loader.load(baseUrl() + "/no-location"));

        assertTrue(error.getMessage().contains("missing a Location header"));
    }

    @Test
    void surfacesNonRedirectFailures() {
        var error = assertThrows(IllegalArgumentException.class, () -> loader.load(baseUrl() + "/missing"));

        assertTrue(error.getMessage().contains("HTTP 404"));
    }

    private String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }
}
