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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class OpenAIImageMediaProviderTest {
    private HttpServer server;
    private OpenAIImageMediaProvider provider;
    private final AtomicReference<Map<String, Object>> responseBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/images/generations", exchange -> {
            var bytes = JsonUtil.toJson(responseBody.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        provider = new OpenAIImageMediaProvider(baseUrl(), "test-token");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void generateImageParsesTokenDetails() {
        var usage = new LinkedHashMap<String, Object>();
        usage.put("total_tokens", 350);
        usage.put("image_count", 1);
        usage.put("input_tokens", 150);
        usage.put("output_tokens", 200);
        usage.put("input_tokens_details", Map.of("text_tokens", 100, "image_tokens", 50));
        responseBody.set(Map.of("data", List.of(Map.of("b64_json", "aW1hZ2U=")), "usage", usage));

        var response = provider.generateImage(new ImageGenerationRequest("gpt-image-2", "a cat", 1, null, null, null, null, null, null, null, null, null));

        assertEquals(350, response.usage().totalTokens());
        assertEquals(1, response.usage().imageCount());
        assertEquals(150, response.usage().inputTokens());
        assertEquals(200, response.usage().outputTokens());
        assertEquals(100, response.usage().inputTextTokens());
        assertEquals(50, response.usage().inputImageTokens());
        assertNull(response.usage().upstreamCostUsd());
    }

    @Test
    void generateImageLeavesTokenDetailsNullWhenAbsent() {
        responseBody.set(Map.of("data", List.of(Map.of("b64_json", "aW1hZ2U=")),
                "usage", Map.of("total_tokens", 350, "image_count", 1)));

        var response = provider.generateImage(new ImageGenerationRequest("gpt-image-2", "a cat", 1, null, null, null, null, null, null, null, null, null));

        assertEquals(350, response.usage().totalTokens());
        assertNull(response.usage().inputTextTokens());
        assertNull(response.usage().inputImageTokens());
    }

    @Test
    void generateImageLeavesUsageNullWhenAbsent() {
        responseBody.set(Map.of("data", List.of(Map.of("b64_json", "aW1hZ2U="))));

        var response = provider.generateImage(new ImageGenerationRequest("gpt-image-2", "a cat", 1, null, null, null, null, null, null, null, null, null));

        assertNull(response.usage());
    }

    private String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostName() + ":" + server.getAddress().getPort();
    }
}
