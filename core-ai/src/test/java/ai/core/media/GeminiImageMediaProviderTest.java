package ai.core.media;

import ai.core.media.domain.ImageGenerationRequest;
import ai.core.utils.JsonUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class GeminiImageMediaProviderTest {
    private HttpServer server;
    private GeminiImageMediaProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/models/imagen-4.0-generate-001:generateContent", exchange -> {
            var body = Map.of(
                    "candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("inlineData", Map.of("data", "aW1hZ2U=")))))),
                    "usageMetadata", Map.of("totalTokenCount", 128, "promptTokenCount", 100, "candidatesTokenCount", 28));
            var bytes = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        provider = new GeminiImageMediaProvider(baseUrl(), "test-key");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void generateImageParsesUsageMetadata() {
        var response = provider.generateImage(new ImageGenerationRequest("imagen-4.0-generate-001", "a cat", 1, null, null, null, null, null, null, null, null, null));

        assertEquals(1, response.data().size());
        assertEquals(128, response.usage().totalTokens());
        assertEquals(100, response.usage().inputTokens());
        assertEquals(28, response.usage().outputTokens());
        assertNull(response.usage().upstreamCostUsd());
    }

    private String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostName() + ":" + server.getAddress().getPort();
    }
}
