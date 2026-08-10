package ai.core.media;

import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.MediaReference;
import ai.core.media.domain.VideoGenerationRequest;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class KieMediaProviderTest {
    private static final String TASK_ID = "task_test_1";

    private HttpServer server;
    private KieMediaProvider provider;
    private final List<Map<String, Object>> createTaskBodies = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> uploadBodies = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> taskState = new AtomicReference<>("generating");
    private final AtomicReference<String> failMsg = new AtomicReference<>("");
    private final AtomicReference<String> taskRejectMsg = new AtomicReference<>();
    private final AtomicInteger recordInfoCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/jobs/createTask", exchange -> {
            createTaskBodies.add(JsonUtil.toMap(body(exchange)));
            if (taskRejectMsg.get() != null) {
                var rejectBody = new LinkedHashMap<String, Object>();
                rejectBody.put("code", 422);
                rejectBody.put("msg", taskRejectMsg.get());
                rejectBody.put("data", null);
                json(exchange, 200, rejectBody);
            } else {
                json(exchange, 200, Map.of("code", 200, "msg", "success", "data", Map.of("taskId", TASK_ID)));
            }
        });
        server.createContext("/api/v1/jobs/recordInfo", exchange -> {
            recordInfoCalls.incrementAndGet();
            if ("success".equals(taskState.get())) {
                var resultJson = "{\"resultUrls\":[\"" + baseUrl() + "/video.mp4\"]}";
                json(exchange, 200, Map.of("code", 200, "msg", "success",
                        "data", Map.of("taskId", TASK_ID, "state", "success", "resultJson", resultJson)));
            } else if ("fail".equals(taskState.get())) {
                json(exchange, 200, Map.of("code", 200, "msg", "success",
                        "data", Map.of("taskId", TASK_ID, "state", "fail", "failCode", "422", "failMsg", failMsg.get())));
            } else {
                json(exchange, 200, Map.of("code", 200, "msg", "success",
                        "data", Map.of("taskId", TASK_ID, "state", taskState.get())));
            }
        });
        server.createContext("/api/file-base64-upload", exchange -> {
            uploadBodies.add(JsonUtil.toMap(body(exchange)));
            json(exchange, 200, Map.of("code", 200, "msg", "File uploaded successfully",
                    "data", Map.of("fileName", "reference.png", "downloadUrl", baseUrl() + "/uploads/reference.png")));
        });
        server.createContext("/video.mp4", exchange -> {
            var bytes = "video-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        provider = new KieMediaProvider(baseUrl(), baseUrl(), "test-token", null);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void generateVideoMapsSizeAndDurationForKling() {
        var response = provider.generateVideo(videoRequest("kling-2.6/text-to-video", "A cat", 5, "1280x720", null));

        assertEquals(TASK_ID, response.id());
        assertEquals("pending", response.status());
        var input = createTaskInput();
        assertEquals("A cat", input.get("prompt"));
        assertEquals("16:9", input.get("aspect_ratio"));
        assertEquals("5", input.get("duration"));
    }

    @Test
    void generateVideoMapsSquareAndPortraitSizes() {
        provider.generateVideo(videoRequest("kling-2.6/text-to-video", "square", null, "1024x1024", null));
        provider.generateVideo(videoRequest("kling-2.6/text-to-video", "portrait", null, "720x1280", null));

        assertEquals("1:1", createTaskInput(0).get("aspect_ratio"));
        assertEquals("9:16", createTaskInput(1).get("aspect_ratio"));
        assertFalse(createTaskInput(0).containsKey("duration"));
    }

    @Test
    void generateVideoUsesIntegerDurationForSeedance2() {
        provider.generateVideo(videoRequest("bytedance/seedance-2-fast", "A cat", 10, null, null));

        assertEquals(10, createTaskInput().get("duration"));
        assertFalse(createTaskInput().containsKey("aspect_ratio"));
    }

    @Test
    void generateVideoRejectsPreviousVideoEditing() {
        var request = new VideoGenerationRequest("kling-2.6/text-to-video", "edit", 5, "1280x720", null, null, "previous-id");

        assertThrows(IllegalArgumentException.class, () -> provider.generateVideo(request));
    }

    @Test
    void generateVideoUploadsBase64ReferenceForSeedance2() {
        var reference = new MediaReference(null, "aGVsbG8=");

        provider.generateVideo(videoRequest("bytedance/seedance-2-fast", "animate", 5, null, List.of(reference)));

        assertEquals(1, uploadBodies.size());
        assertEquals("aGVsbG8=", uploadBodies.getFirst().get("base64Data"));
        var referenceUrls = createTaskInput().get("reference_image_urls");
        assertTrue(referenceUrls instanceof List<?>);
        assertEquals(List.of(baseUrl() + "/uploads/reference.png"), referenceUrls);
    }

    @Test
    void generateVideoPassesHttpUrlReferenceWithoutUpload() {
        var reference = new MediaReference("https://example.com/ref.png", null);

        provider.generateVideo(videoRequest("kling-2.6/text-to-video", "animate", 5, null, List.of(reference)));

        assertTrue(uploadBodies.isEmpty());
        assertEquals(List.of("https://example.com/ref.png"), createTaskInput().get("image_urls"));
    }

    @Test
    void getVideoStatusMapsSuccessToCompleted() {
        taskState.set("success");

        var status = provider.getVideoStatus(TASK_ID);

        assertEquals("completed", status.status());
    }

    @Test
    void getVideoStatusMapsFailureWithMessage() {
        taskState.set("fail");
        failMsg.set("prompt contains prohibited content");

        var status = provider.getVideoStatus(TASK_ID);

        assertEquals("failed", status.status());
        assertEquals("prompt contains prohibited content", status.error());
    }

    @Test
    void downloadVideoDownloadsResultUrl() {
        taskState.set("success");

        var bytes = provider.downloadVideo(TASK_ID);

        assertArrayEquals("video-bytes".getBytes(StandardCharsets.UTF_8), bytes);
    }

    @Test
    void generateVideoReportsBusinessErrorCodeWhenTaskRejected() {
        taskRejectMsg.set("model not found: bytedance/seedance-2.0");

        var error = assertThrows(IllegalStateException.class,
                () -> provider.generateVideo(videoRequest("bytedance/seedance-2.0", "A cat", 5, null, null)));

        assertTrue(error.getMessage().contains("422"));
        assertTrue(error.getMessage().contains("model not found"));
    }

    @Test
    void generateImageIsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> provider.generateImage(new ImageGenerationRequest("model", "prompt", null, null, null,
                        null, null, null, null, null, null, null)));
    }

    private VideoGenerationRequest videoRequest(String model, String prompt, Integer seconds, String size, List<MediaReference> references) {
        return new VideoGenerationRequest(model, prompt, seconds, size, references, null);
    }

    private Map<String, Object> createTaskInput() {
        return createTaskInput(createTaskBodies.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createTaskInput(int index) {
        var input = createTaskBodies.get(index).get("input");
        return (Map<String, Object>) input;
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
