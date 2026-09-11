package ai.core.media;

import ai.core.media.domain.MediaReference;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.MediaReferenceRole;
import ai.core.utils.JsonUtil;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class VertexGeminiOmniMediaProviderTest {
    private HttpServer server;
    private VertexGeminiOmniMediaProvider provider;
    private final AtomicReference<String> interactionBody = new AtomicReference<>();
    private final List<Map<String, Object>> createBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/projects/test-project/locations/global/interactions", exchange -> {
            createBodies.add(JsonUtil.toMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            var bytes = "{\"id\":\"video-1\",\"status\":\"in_progress\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/projects/test-project/locations/global/interactions/video-1", exchange -> {
            var bytes = interactionBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        var baseUrl = "http://localhost:" + server.getAddress().getPort();
        provider = new VertexGeminiOmniMediaProvider(baseUrl, "test-project", "global", new GoogleAccessTokenProvider(null) {
            @Override
            public String accessToken() {
                return "test-token";
            }
        });
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void failedInteractionSurfacesTheErrorsArray() {
        interactionBody.set("""
            {"id":"video-1","status":"failed","object":"interaction",
             "errors":[{"message":"Unable to show the generated video. Try rephrasing the prompt.","code":"content_blocked"}]}""");

        var status = provider.getVideoStatus("video-1");

        assertEquals("failed", status.status());
        assertEquals("content_blocked: Unable to show the generated video. Try rephrasing the prompt.", status.error());
    }

    @Test
    void failedInteractionWithTopLevelErrorObjectStillWorks() {
        interactionBody.set("""
            {"id":"video-1","status":"failed","error":{"message":"quota exceeded"}}""");

        var status = provider.getVideoStatus("video-1");

        assertEquals("failed", status.status());
        assertEquals("quota exceeded", status.error());
    }

    @Test
    void processingInteractionHasNoError() {
        interactionBody.set("""
            {"id":"video-1","status":"in_progress"}""");

        var status = provider.getVideoStatus("video-1");

        assertEquals("processing", status.status());
        assertNull(status.error());
    }

    @Test
    void completedInteractionExposesVideoOutputTokenUsage() {
        interactionBody.set("""
            {"id":"video-1","status":"completed","object":"interaction",
             "usage":{"total_input_tokens":5000,"total_output_tokens":28832,"total_thought_tokens":1200,"total_tokens":35032,
                      "input_tokens_by_modality":[{"modality":"text","tokens":4900},{"modality":"image","tokens":100}],
                      "output_tokens_by_modality":[{"modality":"video","tokens":28832}]}}""");

        var status = provider.getVideoStatus("video-1");

        assertEquals("completed", status.status());
        var usage = status.usage();
        assertNotNull(usage);
        assertEquals(35_032, usage.totalTokens());
        assertEquals(5_000, usage.inputTokens());
        assertEquals(30_032, usage.outputTokens());
        assertEquals(4_900, usage.inputTextTokens());
        assertEquals(100, usage.inputImageTokens());
        assertEquals(28_832, usage.outputVideoTokens());
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstAndLastFrameLeadTheInputAndMakeItImageToVideo() {
        var sheet = new MediaReference("https://example.com/sheet.png", null, null, "char_1", MediaReferenceRole.SUBJECT, MediaModality.IMAGE);
        var last = new MediaReference("https://example.com/end.png", null, null, "last_frame", MediaReferenceRole.LAST_FRAME, MediaModality.IMAGE);
        var first = new MediaReference("https://example.com/kf.png", null, null, "first_frame", MediaReferenceRole.FIRST_FRAME, MediaModality.IMAGE);

        provider.generateVideo(new VideoGenerationRequest("gemini-omni-1.1-flash", "a smooth transition", 8, "1080x1920", List.of(sheet, last, first), null));

        var body = createBodies.getFirst();
        var input = (List<Map<String, Object>>) body.get("input");
        assertEquals(List.of("image", "image", "image", "text"), input.stream().map(item -> item.get("type")).toList(), "images first, text last");
        assertEquals("https://example.com/kf.png", input.get(0).get("uri"), "first frame is input[0]");
        assertEquals("https://example.com/end.png", input.get(1).get("uri"), "last frame is input[1]");
        assertEquals("https://example.com/sheet.png", input.get(2).get("uri"));
        var videoConfig = (Map<String, Object>) ((Map<String, Object>) body.get("generation_config")).get("video_config");
        assertEquals("image_to_video", videoConfig.get("task"));
        var format = ((List<Map<String, Object>>) body.get("response_format")).getFirst();
        assertEquals("9:16", format.get("aspect_ratio"));
        assertEquals("1080p", format.get("resolution"));
        assertEquals("8s", format.get("duration"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void referencesWithoutFramesStayReferenceToVideo() {
        var sheet = new MediaReference("https://example.com/sheet.png", null, null, "char_1", MediaReferenceRole.SUBJECT, MediaModality.IMAGE);

        provider.generateVideo(new VideoGenerationRequest("gemini-omni-1.1-flash", "a cat", null, null, List.of(sheet), null));
        provider.generateVideo(new VideoGenerationRequest("gemini-omni-1.1-flash", "a cat", null, null, null, null));

        var withReference = (Map<String, Object>) ((Map<String, Object>) createBodies.get(0).get("generation_config")).get("video_config");
        var textOnly = (Map<String, Object>) ((Map<String, Object>) createBodies.get(1).get("generation_config")).get("video_config");
        assertEquals("reference_to_video", withReference.get("task"));
        assertEquals("text_to_video", textOnly.get("task"));
        var format = ((List<Map<String, Object>>) createBodies.get(1).get("response_format")).getFirst();
        assertNull(format.get("resolution"), "no size, no resolution: the model keeps its default");
    }
}
