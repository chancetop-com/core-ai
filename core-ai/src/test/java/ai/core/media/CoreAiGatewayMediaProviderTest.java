package ai.core.media;

import ai.core.llm.providers.LiteLLMMediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.MediaReference;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.MediaReferenceRole;
import ai.core.utils.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class CoreAiGatewayMediaProviderTest {
    private static final String IMAGE_RESPONSE = """
            {
              "data": [{"b64_json": "aW1n", "url": "https://cdn.example.com/out.png", "revised_prompt": "revised"}],
              "usage": {"total_tokens": 350, "image_count": 1, "upstream_cost_usd": 0.25},
              "interaction_id": "interaction-1",
              "media_id": "gateway-media-v1.img.am9iLTE",
              "notes": ["dropped 2 references: model accepts 4"]
            }
            """;

    private HttpServer server;
    private final AtomicReference<Map<String, Object>> imageBody = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> videoBody = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> imageHeaders = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/gateway/v1/images/generations", exchange -> {
            imageBody.set(JsonUtil.toMap(body(exchange)));
            imageHeaders.set(exchange.getRequestHeaders());
            json(exchange, 200, IMAGE_RESPONSE);
        });
        server.createContext("/api/gateway/v1/videos", exchange -> {
            videoBody.set(JsonUtil.toMap(body(exchange)));
            json(exchange, 200, """
                    {"id":"gateway-media-v1.vid.abc","status":"queued","created_at":123,"usage":{"video_seconds":5}}
                    """);
        });
        server.createContext("/api/gateway/v1/videos/gateway-media-v1.vid.abc", exchange -> json(exchange, 200, """
                {"id":"gateway-media-v1.vid.abc","status":"completed","progress":100,
                 "credits_consumed":12.5,"upstream_cost_usd":0.4,"completed_at":456,
                 "usage":{"video_seconds":5,"upstream_cost_usd":0.4}}
                """));
        server.createContext("/api/gateway/v1/videos/gateway-media-v1.vid.abc/content", exchange -> {
            var bytes = new byte[]{0, 0, 0, 1};
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsExplicitImageBodyWithReferencesAndParsesTheHandle() {
        var provider = new CoreAiGatewayMediaProvider(baseUrl(), "key-1", "session-7");
        var request = new ImageGenerationRequest("gpt-image-2.5", "turn the lemon into a lime", 1, "1024x1536",
                "high", "png", 90, "transparent",
                List.of(new MediaReference(null, null, "gateway-media-v1.img.source", "source",
                        MediaReferenceRole.SUBJECT, MediaModality.IMAGE)),
                new MediaReference(null, "data:image/png;base64,AAAA"),
                "{\"input\":{\"watermark\":false}}", "interaction-0");

        var response = provider.generateImage(request);

        assertEquals("gateway-media-v1.img.am9iLTE", response.mediaId());
        assertEquals("interaction-1", response.interactionId());
        assertEquals(1, response.data().size());
        assertEquals("aW1n", response.data().getFirst().b64Json());
        assertEquals(350, response.usage().totalTokens());
        assertEquals(0.25, response.usage().upstreamCostUsd());
        assertEquals(List.of("dropped 2 references: model accepts 4"), response.notes());

        var body = imageBody.get();
        assertEquals("gpt-image-2.5", body.get("model"));
        assertEquals(90, body.get("output_compression"));
        assertFalse(body.containsKey("provider_extra"), "provider_extra is merged into the top level");
        assertEquals(Boolean.FALSE, ((Map<?, ?>) body.get("input")).get("watermark"));
        var images = (List<?>) body.get("input_images");
        var reference = (Map<?, ?>) images.getFirst();
        assertEquals("gateway-media-v1.img.source", reference.get("media_id"));
        assertEquals("subject", reference.get("role"));
        assertEquals("image", reference.get("modality"));
        assertEquals("data:image/png;base64,AAAA", ((Map<?, ?>) body.get("mask")).get("b64Json"));
        assertEquals("Bearer key-1", header("Authorization"));
        assertEquals("cli", header("X-Core-AI-Client"));
        assertEquals("session-7", header("x-session-id"));
    }

    @Test
    void omitsEmptyReferenceCollections() {
        var provider = new CoreAiGatewayMediaProvider(baseUrl(), "key-1");
        provider.generateImage(new ImageGenerationRequest("m", "p", null, null, null, null, null, null,
                null, null, null, null));

        var body = imageBody.get();
        assertFalse(body.containsKey("input_images"));
        assertFalse(body.containsKey("mask"));
        assertTrue(provider.acceptsImageReferences());
        assertTrue(provider.withSessionId("s").acceptsImageReferences());
    }

    @Test
    void runsTheVideoLifecycleWithExplicitReferences() {
        var provider = new CoreAiGatewayMediaProvider(baseUrl(), "key-1", "session-7");
        var submitted = provider.generateVideo(new VideoGenerationRequest("seedance-2.5", "make it move", 5,
                "1280x720",
                List.of(new MediaReference(null, null, "gateway-media-v1.img.abc", null,
                        MediaReferenceRole.FIRST_FRAME, MediaModality.IMAGE)),
                "{\"input\":{\"generate_audio\":true}}", "gateway-media-v1.vid.previous"));

        assertEquals("gateway-media-v1.vid.abc", submitted.id());
        assertEquals("queued", submitted.status());
        var body = videoBody.get();
        var references = (List<?>) body.get("input_references");
        assertEquals("first_frame", ((Map<?, ?>) references.getFirst()).get("role"));
        assertEquals("gateway-media-v1.vid.previous", body.get("previous_video_id"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) body.get("input")).get("generate_audio"));

        VideoStatusResponse status = provider.getVideoStatus("gateway-media-v1.vid.abc");
        assertEquals("completed", status.status());
        assertEquals(12.5, status.creditsConsumed());
        assertEquals(0.4, status.upstreamCostUsd());
        assertArrayEquals(new byte[]{0, 0, 0, 1}, provider.downloadVideo("gateway-media-v1.vid.abc"));
    }

    @Test
    void reportsHttpFailuresWithoutDumpingTheWholeBody() {
        var provider = new LiteLLMMediaProvider(baseUrl() + "/missing", "key-1");
        var exception = assertThrows(RuntimeException.class, () -> provider.getVideoStatus("vid"));
        assertTrue(exception.getMessage().contains("HTTP 404"), exception.getMessage());
        assertNotNull(exception.getMessage());
    }

    private String header(String name) {
        for (var entry : imageHeaders.get().entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue().getFirst();
        }
        return null;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/gateway/v1";
    }

    private String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
