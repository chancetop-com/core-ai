package ai.core.media;

import ai.core.media.domain.ImageGenerationRequest;
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
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class ArkMediaProviderTest {
    private static final String SEEDANCE_2_5 = "doubao-seedance-2-5-260628";
    private static final String TASK_ID = "cgt-20260925120000-test1";

    private HttpServer server;
    private ArkMediaProvider provider;
    private final List<Map<String, Object>> createBodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> taskStatus = new AtomicReference<>("queued");
    private final AtomicReference<String> taskJson = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> createResponse = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v3/contents/generations/tasks", exchange -> {
            createBodies.add(JsonUtil.toMap(body(exchange)));
            json(exchange, 200, createResponse.get() == null ? Map.of("id", TASK_ID) : createResponse.get());
        });
        server.createContext("/api/v3/contents/generations/tasks/" + TASK_ID, exchange -> {
            if (taskJson.get() != null) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                var bytes = taskJson.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
                return;
            }
            json(exchange, 200, Map.of("id", TASK_ID, "model", SEEDANCE_2_5, "status", taskStatus.get(), "content", Map.of()));
        });
        server.createContext("/video.mp4", exchange -> {
            var bytes = "ark-video-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        // the Ark base URL ends at /api/v3 (that is what the provider row and the /models probe share)
        provider = new ArkMediaProvider(baseUrl() + "/api/v3", "test-key");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void generateVideoSendsTheContentArrayShapeArkDocuments() {
        var response = provider.generateVideo(request(SEEDANCE_2_5, "A cat on a roof", 10, "1920x1080", null, null));

        assertEquals(TASK_ID, response.id());
        assertEquals("pending", response.status());
        var body = createBody();
        assertEquals(SEEDANCE_2_5, body.get("model"));
        assertEquals(10, body.get("duration"));
        assertEquals("16:9", body.get("ratio"));
        assertEquals("1080p", body.get("resolution"));
        assertEquals(Boolean.TRUE, body.get("generate_audio"), "native audio is why the seedance family is picked");
        var content = content();
        assertEquals(Map.of("type", "text", "text", "A cat on a roof"), content.getFirst());
    }

    @Test
    void generateVideoSendsOnlyTheParametersTheCallerActuallyGave() {
        provider.generateVideo(request(SEEDANCE_2_5, "A cat", null, null, null, null));

        var body = createBody();
        assertFalse(body.containsKey("duration"), "without seconds the model picks its own default");
        assertFalse(body.containsKey("ratio"));
        assertFalse(body.containsKey("resolution"));
    }

    @Test
    void generateVideoSnapsTheDurationToWhatSeedanceAccepts() {
        provider.generateVideo(request(SEEDANCE_2_5, "short", 3, null, null, null));
        provider.generateVideo(request(SEEDANCE_2_5, "long", 45, null, null, null));

        assertEquals(4, createBody(0).get("duration"), "a 3s clip is rejected upstream");
        assertEquals(30, createBody(1).get("duration"), "2.5 renders at most 30s");
    }

    @Test
    void generateVideoSendsTheNearestDocumentedRatioAndTheResolutionTier() {
        provider.generateVideo(request(SEEDANCE_2_5, "vertical", 5, "1080x1920", null, null));
        provider.generateVideo(request(SEEDANCE_2_5, "post", 5, "1080x1350", null, null));
        provider.generateVideo(request(SEEDANCE_2_5, "square", 5, "1024x1024", null, null));
        provider.generateVideo(request(SEEDANCE_2_5, "small", 5, "640x360", null, null));

        // a 4:5 request would have rendered as 9:16 under the three-way landscape/portrait/square split
        assertEquals("9:16", createBody(0).get("ratio"));
        assertEquals("3:4", createBody(1).get("ratio"));
        assertEquals("1:1", createBody(2).get("ratio"));
        assertEquals("480p", createBody(3).get("resolution"), "a size below every tier gets the smallest one");
    }

    @Test
    void generateVideoMapsFramesToArkRolesAndAdaptiveRatio() {
        var first = new MediaReference("https://example.com/first.png", null, null, null, MediaReferenceRole.FIRST_FRAME, null);
        var last = new MediaReference("https://example.com/last.png", null, null, null, MediaReferenceRole.LAST_FRAME, null);

        provider.generateVideo(request(SEEDANCE_2_5, "walk through the gate", 6, "1080x1920", List.of(first, last), null));

        var content = content();
        assertEquals(Map.of("type", "image_url", "image_url", Map.of("url", "https://example.com/first.png"), "role", "first_frame"), content.get(1));
        assertEquals("last_frame", role(content.get(2)));
        assertEquals("adaptive", createBody().get("ratio"), "frame mode derives the ratio from the frame");
        assertEquals("1080p", createBody().get("resolution"), "the tier is independent of the ratio");
    }

    @Test
    void generateVideoSendsTheOpeningFrameAsReferenceOneWhenIdentityReferencesRideAlong() {
        var frame = new MediaReference("https://example.com/frame.png", null, null, null, MediaReferenceRole.FIRST_FRAME, null);
        var identity = new MediaReference("https://example.com/sheet.png", null, null, null, MediaReferenceRole.SUBJECT, null);

        provider.generateVideo(request(SEEDANCE_2_5, "@image1 opens on the woman from @image2", 6, null, List.of(frame, identity), null));

        var content = content();
        // the frame keeps slot 1 (the compiled token order) and is named as the opening frame by the prompt,
        // which is the multimodal-reference route's documented way out of frame/reference exclusivity
        assertEquals("reference_image", role(content.get(1)));
        assertEquals("https://example.com/frame.png", url(content.get(1)));
        assertEquals("reference_image", role(content.get(2)));
        assertEquals("https://example.com/sheet.png", url(content.get(2)));
        assertEquals("adaptive", createBody().get("ratio"));
    }

    @Test
    void generateVideoRejectsFrameAndReferencesOnAFamilyWithoutTheReferenceRoute() {
        var frame = new MediaReference("https://example.com/frame.png", null, null, null, MediaReferenceRole.FIRST_FRAME, null);
        var identity = new MediaReference("https://example.com/sheet.png", null, null, null, MediaReferenceRole.SUBJECT, null);

        var error = assertThrows(IllegalArgumentException.class,
                () -> provider.generateVideo(request("doubao-seedance-1-0-pro-250528", "animate", 5, null, List.of(frame, identity), null)));

        assertTrue(error.getMessage().contains("not both"));
    }

    @Test
    void generateVideoWrapsBase64ReferencesAsDataUrls() {
        var reference = new MediaReference(null, "aGVsbG8=", null, null, MediaReferenceRole.SUBJECT, MediaModality.IMAGE);

        provider.generateVideo(request(SEEDANCE_2_5, "animate", 5, null, List.of(reference), null));

        assertEquals("data:image/png;base64,aGVsbG8=", url(content().get(1)));
    }

    @Test
    void generateVideoSendsVideoAndAudioReferencesAsTheirOwnFacingItems() {
        var video = new MediaReference("https://example.com/source.mp4", null, null, null, MediaReferenceRole.CAMERA, MediaModality.VIDEO);
        var audio = new MediaReference("https://example.com/voice.mp3", null, null, null, MediaReferenceRole.AUDIO, MediaModality.AUDIO);

        provider.generateVideo(request(SEEDANCE_2_5, "continue the move", 5, null, List.of(video, audio), null));

        var content = content();
        assertEquals("video_url", content.get(1).get("type"));
        assertEquals("reference_video", role(content.get(1)));
        assertEquals("audio_url", content.get(2).get("type"));
        assertEquals("reference_audio", role(content.get(2)));
    }

    @Test
    void generateVideoTranslatesTheKieDialectReferenceArraysFromProviderExtra() {
        provider.generateVideo(request(SEEDANCE_2_5, "continue", 5, null, null,
                "{\"input\":{\"reference_video_urls\":[\"https://example.com/source.mp4\"],"
                        + "\"reference_audio_urls\":[\"https://example.com/voice.mp3\"],\"return_last_frame\":true}}"));

        var content = content();
        assertEquals("reference_video", role(content.get(1)));
        assertEquals("reference_audio", role(content.get(2)));
        assertEquals(Boolean.TRUE, createBody().get("return_last_frame"), "a non-reference key rides on the request body, there is no input object");
        assertFalse(createBody().containsKey("input"));
    }

    @Test
    void generateVideoLetsProviderExtraOverrideTheDerivedParameters() {
        provider.generateVideo(request(SEEDANCE_2_5, "explicit", 5, "1080x1920", null,
                "{\"resolution\":\"480p\",\"generate_audio\":false,\"watermark\":true}"));

        var body = createBody();
        assertEquals("480p", body.get("resolution"));
        assertEquals(Boolean.FALSE, body.get("generate_audio"));
        assertEquals(Boolean.TRUE, body.get("watermark"));
    }

    @Test
    void generateVideoRejectsProviderExtraThatWouldReplaceThePromptOrReferences() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> provider.generateVideo(request(SEEDANCE_2_5, "A cat", 5, null, null, "{\"content\":[{\"type\":\"text\",\"text\":\"hijacked\"}]}")));

        assertTrue(error.getMessage().contains("must not set \"content\""));
    }

    @Test
    void generateVideoRejectsConversationalVideoIds() {
        var request = new VideoGenerationRequest(SEEDANCE_2_5, "edit it", 5, null, null, null, "video-abc");

        var error = assertThrows(IllegalArgumentException.class, () -> provider.generateVideo(request));

        assertTrue(error.getMessage().contains("previous_video_id"));
    }

    @Test
    void getVideoStatusMapsArkStatesAndUsage() {
        taskStatus.set("running");
        assertEquals("processing", provider.getVideoStatus(TASK_ID).status());
        taskStatus.set("queued");
        assertEquals("processing", provider.getVideoStatus(TASK_ID).status());
        taskStatus.set("failed");
        assertEquals("failed", provider.getVideoStatus(TASK_ID).status());
        taskStatus.set("expired");
        assertEquals("failed", provider.getVideoStatus(TASK_ID).status());

        taskJson.set(JsonUtil.toJson(Map.of("id", TASK_ID, "status", "succeeded",
                "usage", Map.of("completion_tokens", 115200, "total_tokens", 115200),
                "content", Map.of("video_url", baseUrl() + "/video.mp4"),
                "duration", 5, "resolution", "480p", "ratio", "16:9")));
        var status = provider.getVideoStatus(TASK_ID);

        assertEquals("completed", status.status());
        assertEquals(115200, status.usage().outputTokens().intValue());
        assertEquals(115200, status.usage().outputVideoTokens().intValue(), "the produced tokens are the billed video tokens");
    }

    @Test
    void getVideoStatusReportsTheUpstreamError() {
        taskJson.set(JsonUtil.toJson(Map.of("id", TASK_ID, "status", "failed",
                "error", Map.of("code", "InvalidParameter.TaskTypeConstraint", "message", "aspect_ratio conflicts with the task type"))));

        var status = provider.getVideoStatus(TASK_ID);

        assertEquals("failed", status.status());
        assertTrue(status.error().contains("InvalidParameter.TaskTypeConstraint"));
        assertTrue(status.error().contains("aspect_ratio conflicts with the task type"));
    }

    @Test
    void downloadVideoFetchesTheCompletedTaskUrl() {
        taskJson.set(JsonUtil.toJson(Map.of("id", TASK_ID, "status", "succeeded", "content", Map.of("video_url", baseUrl() + "/video.mp4"))));

        assertArrayEquals("ark-video-bytes".getBytes(StandardCharsets.UTF_8), provider.downloadVideo(TASK_ID));
    }

    @Test
    void downloadVideoFailsWhileTheTaskIsStillRunning() {
        taskStatus.set("running");

        assertThrows(IllegalStateException.class, () -> provider.downloadVideo(TASK_ID));
    }

    @Test
    void generateImageIsNotSupportedByTheArkVideoProvider() {
        var request = new ImageGenerationRequest(SEEDANCE_2_5, "a poster", null, null, null, null, null, null, null, null, null, null);

        assertThrows(UnsupportedOperationException.class, () -> provider.generateImage(request));
        assertFalse(provider.acceptsImageReferences(), "an image edit must be refused by the router, not sent upstream");
    }

    @Test
    void generateVideoFailsWhenTheTaskIdIsMissing() {
        createResponse.set(Map.of("code", "InternalError"));

        var error = assertThrows(IllegalStateException.class,
                () -> provider.generateVideo(request(SEEDANCE_2_5, "A cat", 5, null, null, null)));

        assertTrue(error.getMessage().contains("missing id"));
    }

    private VideoGenerationRequest request(String model, String prompt, Integer seconds, String size,
                                           List<MediaReference> references, String providerExtra) {
        return new VideoGenerationRequest(model, prompt, seconds, size, references, providerExtra);
    }

    private Map<String, Object> createBody() {
        return createBody(createBodies.size() - 1);
    }

    private Map<String, Object> createBody(int index) {
        return createBodies.get(index);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content() {
        return (List<Map<String, Object>>) createBody().get("content");
    }

    private String role(Map<String, Object> item) {
        return (String) item.get("role");
    }

    @SuppressWarnings("unchecked")
    private String url(Map<String, Object> item) {
        return (String) ((Map<String, Object>) item.get(item.get("type"))).get("url");
    }

    private String baseUrl() {
        return "http://" + InetAddress.getLoopbackAddress().getHostName() + ":" + server.getAddress().getPort();
    }

    private String body(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void json(com.sun.net.httpserver.HttpExchange exchange, int statusCode, Map<String, Object> body) throws IOException {
        var bytes = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
