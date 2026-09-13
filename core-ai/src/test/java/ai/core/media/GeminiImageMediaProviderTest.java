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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class GeminiImageMediaProviderTest {
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private HttpServer server;
    private GeminiImageMediaProvider provider;
    private Map<String, Object> response;

    @BeforeEach
    void setUp() throws IOException {
        response = Map.of(
                "candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("inlineData", Map.of("data", "aW1hZ2U=")))))),
                "usageMetadata", Map.of("totalTokenCount", 128, "promptTokenCount", 100, "candidatesTokenCount", 28));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            lastRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var bytes = JsonUtil.toJson(response).getBytes(StandardCharsets.UTF_8);
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
        var response = provider.generateImage(request("imagen-4.0-generate-001", "a cat", null));

        assertEquals(1, response.data().size());
        assertEquals(128, response.usage().totalTokens());
        assertEquals(100, response.usage().inputTokens());
        assertEquals(28, response.usage().outputTokens());
        assertNull(response.usage().upstreamCostUsd());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsImageConfigDerivedFromTheRequestedSize() {
        provider.generateImage(request("gemini-3.1-flash-image", "a cat", "1080x1920"));

        var config = (Map<String, Object>) body().get("generationConfig");
        assertEquals(List.of("TEXT", "IMAGE"), config.get("responseModalities"));
        var imageConfig = (Map<String, Object>) config.get("imageConfig");
        assertEquals("9:16", imageConfig.get("aspectRatio"));
        assertEquals("2K", imageConfig.get("imageSize"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void leavesTheShapeToTheModelWhenNoSizeIsRequested() {
        provider.generateImage(request("gemini-3.1-flash-image", "a cat", null));

        var config = (Map<String, Object>) body().get("generationConfig");
        assertEquals(List.of("TEXT", "IMAGE"), config.get("responseModalities"));
        assertFalse(config.containsKey("imageConfig"));
    }

    @Test
    void keepsTheFinalImageOfACandidateOnly() {
        response = Map.of("candidates", List.of(Map.of("content", Map.of("parts", List.of(
                Map.of("inlineData", Map.of("data", "aW50ZXJtZWRpYXRl")),
                Map.of("text", "here is the render"),
                Map.of("inlineData", Map.of("data", "ZmluYWw=")))))));

        var generated = provider.generateImage(request("gemini-3.1-flash-image", "a cat", null));

        assertEquals(1, generated.data().size());
        assertEquals("ZmluYWw=", generated.data().getFirst().b64Json());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body() {
        return (Map<String, Object>) JsonUtil.fromJson(Map.class, lastRequest.get());
    }

    private ImageGenerationRequest request(String model, String prompt, String size) {
        return new ImageGenerationRequest(model, prompt, 1, size, null, null, null, null, null, null, null, null);
    }

    private String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostName() + ":" + server.getAddress().getPort();
    }
}
