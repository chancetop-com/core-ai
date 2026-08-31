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
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private final AtomicReference<Integer> taskRejectCode = new AtomicReference<>(422);
    private final AtomicReference<Double> creditsConsumed = new AtomicReference<>();
    private final AtomicReference<String> resultPath = new AtomicReference<>("/video.mp4");
    private final AtomicInteger recordInfoCalls = new AtomicInteger();
    private final AtomicInteger successAfterCalls = new AtomicInteger();
    private final AtomicInteger recordInfoFailures = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/jobs/createTask", exchange -> {
            createTaskBodies.add(JsonUtil.toMap(body(exchange)));
            if (taskRejectMsg.get() != null) {
                var rejectBody = new LinkedHashMap<String, Object>();
                rejectBody.put("code", taskRejectCode.get());
                rejectBody.put("msg", taskRejectMsg.get());
                rejectBody.put("data", null);
                json(exchange, 200, rejectBody);
            } else {
                json(exchange, 200, Map.of("code", 200, "msg", "success", "data", Map.of("taskId", TASK_ID)));
            }
        });
        server.createContext("/api/v1/jobs/recordInfo", exchange -> {
            var calls = recordInfoCalls.incrementAndGet();
            if (recordInfoFailures.getAndUpdate(remaining -> remaining > 0 ? remaining - 1 : 0) > 0) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            if (successAfterCalls.get() > 0 && calls >= successAfterCalls.get()) taskState.set("success");
            if ("success".equals(taskState.get())) {
                var resultJson = "{\"resultUrls\":[\"" + baseUrl() + resultPath.get() + "\"]}";
                var data = new LinkedHashMap<String, Object>();
                data.put("taskId", TASK_ID);
                data.put("state", "success");
                data.put("resultJson", resultJson);
                if (creditsConsumed.get() != null) data.put("creditsConsumed", creditsConsumed.get());
                json(exchange, 200, Map.of("code", 200, "msg", "success", "data", data));
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
        server.createContext("/image.png", exchange -> {
            var bytes = "image-bytes".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        provider = new KieMediaProvider(baseUrl(), baseUrl(), "test-token", null);
        provider.imagePollInterval = Duration.ofMillis(10);
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
    void generateVideoSendsTheResolutionTierTheSizeCovers() {
        provider.generateVideo(videoRequest("bytedance/seedance-2-5", "vertical", 5, "1080x1920", null));
        provider.generateVideo(videoRequest("bytedance/seedance-2-5", "small", 5, "640x360", null));
        provider.generateVideo(videoRequest("wan/2-7-text-to-video", "wan has no 480 tier", 5, "854x480", null));

        // without this the model silently used its own default (720p) whatever size was asked for
        assertEquals("1080p", createTaskInput(0).get("resolution"));
        assertEquals("9:16", createTaskInput(0).get("aspect_ratio"), "the tier sits next to the ratio, it does not replace it");
        assertEquals("480p", createTaskInput(1).get("resolution"), "a size below every tier gets the smallest one");
        assertEquals("720p", createTaskInput(2).get("resolution"), "wan documents 720p/1080p only");
    }

    @Test
    void generateVideoOmitsResolutionWithoutASizeOrAVerifiedVocabulary() {
        provider.generateVideo(videoRequest("bytedance/seedance-2-5", "no size", 5, null, null));
        provider.generateVideo(videoRequest("kling-2.6/text-to-video", "unverified family", 5, "1280x720", null));

        assertFalse(createTaskInput(0).containsKey("resolution"), "no size means no tier to pick");
        assertFalse(createTaskInput(1).containsKey("resolution"), "families we have not verified keep their own default");
    }

    @Test
    void generateVideoLetsProviderExtraOverrideTheResolution() {
        provider.generateVideo(new VideoGenerationRequest("bytedance/seedance-2-5", "explicit", 5, "1080x1920",
            null, "{\"input\":{\"resolution\":\"480p\"}}"));

        assertEquals("480p", createTaskInput().get("resolution"), "an explicit caller wins over the derived tier");
    }

    @Test
    void generateVideoMapsMinimaxH3ReferenceToVideoReferencesAndDuration() {
        var reference = new MediaReference("https://example.com/ref.png", null);

        provider.generateVideo(videoRequest("minimax-h3/reference-to-video", "animate", 10, null, List.of(reference)));

        assertEquals(10, createTaskInput().get("duration"));
        assertEquals(List.of("https://example.com/ref.png"), createTaskInput().get("reference_image_urls"));
    }

    @Test
    void generateVideoMapsMinimaxH3ImageToVideoReferencesToFirstAndLastFrame() {
        var first = new MediaReference("https://example.com/first.png", null);
        var last = new MediaReference("https://example.com/last.png", null);

        provider.generateVideo(videoRequest("minimax-h3/image-to-video", "animate", 8, null, List.of(first, last)));

        assertEquals(8, createTaskInput().get("duration"));
        assertEquals("https://example.com/first.png", createTaskInput().get("first_frame_url"));
        assertEquals("https://example.com/last.png", createTaskInput().get("last_frame_url"));
    }

    @Test
    void generateVideoRejectsReferencesForMinimaxH3TextToVideo() {
        var reference = new MediaReference("https://example.com/ref.png", null);

        var error = assertThrows(IllegalArgumentException.class,
                () -> provider.generateVideo(videoRequest("minimax-h3/text-to-video", "A cat", 5, null, List.of(reference))));

        assertTrue(error.getMessage().contains("does not accept reference images"));
    }

    @Test
    void generateVideoRejectsMultipleReferencesForSingleImageFamily() {
        var first = new MediaReference("https://example.com/1.png", null);
        var second = new MediaReference("https://example.com/2.png", null);

        var error = assertThrows(IllegalArgumentException.class,
                () -> provider.generateVideo(videoRequest("hailuo/2-3-image-to-video-pro", "animate", 6, null, List.of(first, second))));

        assertTrue(error.getMessage().contains("exactly one reference image"));
    }

    @Test
    void generateVideoMapsSingleImageFamilyToImageUrl() {
        var reference = new MediaReference("https://example.com/ref.png", null);

        provider.generateVideo(videoRequest("hailuo/2-3-image-to-video-pro", "animate", 6, null, List.of(reference)));

        assertEquals("https://example.com/ref.png", createTaskInput().get("image_url"));
        assertEquals("6", createTaskInput().get("duration"));
    }

    @Test
    void generateVideoMapsWan27ImageToVideoToFirstFrameWithIntegerDuration() {
        var reference = new MediaReference("https://example.com/frame.png", null);

        provider.generateVideo(videoRequest("wan/2-7-image-to-video", "animate", 5, null, List.of(reference)));

        assertEquals(5, createTaskInput().get("duration"));
        assertEquals("https://example.com/frame.png", createTaskInput().get("first_frame_url"));
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
    void getVideoStatusMapsCreditsConsumed() {
        taskState.set("success");
        creditsConsumed.set(12.5);

        var status = provider.getVideoStatus(TASK_ID);

        assertEquals(12.5, status.creditsConsumed());
        assertNull(status.upstreamCostUsd());
    }

    @Test
    void getVideoStatusLeavesCreditsConsumedNullWhenAbsent() {
        taskState.set("success");
        creditsConsumed.set(null);

        var status = provider.getVideoStatus(TASK_ID);

        assertNull(status.creditsConsumed());
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
    void generateVideoAppendsModelHintToParameterError() {
        taskRejectMsg.set("resolution is not within the range of allowed options");

        var error = assertThrows(IllegalStateException.class,
                () -> provider.generateVideo(videoRequest("minimax-h3/reference-to-video", "A cat", 5, null, null)));

        assertTrue(error.getMessage().contains("resolution is not within the range"));
        assertTrue(error.getMessage().contains("Allowed parameters for minimax-h3/reference-to-video"));
        assertTrue(error.getMessage().contains("768P/2K"));
    }

    @Test
    void generateVideoDoesNotAppendHintToNonParameterError() {
        taskRejectCode.set(402);
        taskRejectMsg.set("insufficient credits");

        var error = assertThrows(IllegalStateException.class,
                () -> provider.generateVideo(videoRequest("minimax-h3/reference-to-video", "A cat", 5, null, null)));

        assertTrue(error.getMessage().contains("402"));
        assertFalse(error.getMessage().contains("Allowed parameters"));
    }

    @Test
    void generateImageMapsSizeAndQualityAndReturnsDownloadedImage() {
        imageTask();

        var response = provider.generateImage(imageRequest("seedream/5-pro-text-to-image", "a cafe", "1024x1536", "high", null));

        var input = createTaskInput();
        assertEquals("a cafe", input.get("prompt"));
        assertEquals("2:3", input.get("aspect_ratio"));
        assertEquals("high", input.get("quality"));
        assertEquals(1, response.data().size());
        var image = response.data().getFirst();
        assertEquals(baseUrl() + "/image.png", image.url());
        assertArrayEquals("image-bytes".getBytes(StandardCharsets.UTF_8), Base64.getDecoder().decode(image.b64Json()));
        assertEquals(1, response.usage().imageCount());
    }

    @Test
    void generateImageDefaultsRequiredAspectRatioAndQuality() {
        imageTask();

        provider.generateImage(imageRequest("seedream/5-lite-text-to-image", "a cafe", null, null, null));

        assertEquals("1:1", createTaskInput().get("aspect_ratio"));
        assertEquals("basic", createTaskInput().get("quality"));
    }

    @Test
    void generateImageFoldsOpenAIQualityOntoKieScale() {
        imageTask();

        provider.generateImage(imageRequest("seedream/5-lite-text-to-image", "a cafe", null, "medium", null));

        assertEquals("basic", createTaskInput().get("quality"));
    }

    @Test
    void generateImageRejectsUnknownQuality() {
        assertThrows(IllegalArgumentException.class,
                () -> provider.generateImage(imageRequest("seedream/5-pro-text-to-image", "a cafe", null, "cinematic", null)));
    }

    @Test
    void generateImageUploadsBase64InputImageToImageUrls() {
        imageTask();
        var reference = new MediaReference(null, "aGVsbG8=");

        provider.generateImage(imageRequest("seedream/5-pro-image-to-image", "make it glass", "1024x1024", null, List.of(reference)));

        assertEquals(1, uploadBodies.size());
        assertEquals(List.of(baseUrl() + "/uploads/reference.png"), createTaskInput().get("image_urls"));
    }

    @Test
    void generateImageRejectsInputImagesForTextToImageModel() {
        var reference = new MediaReference("https://example.com/ref.png", null);

        var error = assertThrows(IllegalArgumentException.class,
                () -> provider.generateImage(imageRequest("seedream/5-pro-text-to-image", "edit", null, null, List.of(reference))));

        assertTrue(error.getMessage().contains("does not accept input images"));
    }

    @Test
    void generateImageRejectsMultipleImagesPerTask() {
        var request = new ImageGenerationRequest("seedream/5-pro-text-to-image", "a cafe", 2, null, null,
                null, null, null, null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> provider.generateImage(request));
    }

    @Test
    void generateImagePollsUntilTaskCompletes() {
        resultPath.set("/image.png");
        taskState.set("generating");
        successAfterCalls.set(3);

        var response = provider.generateImage(imageRequest("seedream/5-pro-text-to-image", "a cafe", null, null, null));

        assertEquals(3, recordInfoCalls.get());
        assertEquals(1, response.data().size());
    }

    @Test
    void generateImageRetriesTransientPollFailures() {
        imageTask();
        recordInfoFailures.set(2);

        var response = provider.generateImage(imageRequest("seedream/5-pro-text-to-image", "a cafe", null, null, null));

        assertEquals(1, response.data().size());
        assertEquals(3, recordInfoCalls.get(), "two failed polls must be retried, not abort the running task");
    }

    @Test
    void generateImageGivesUpAfterRepeatedPollFailures() {
        imageTask();
        recordInfoFailures.set(99);

        assertThrows(RuntimeException.class,
                () -> provider.generateImage(imageRequest("seedream/5-pro-text-to-image", "a cafe", null, null, null)));
    }

    @Test
    void generateImageUploadsBase64WithAFileExtension() {
        imageTask();
        var reference = new MediaReference(null, "data:image/jpeg;base64,aGVsbG8=");

        provider.generateImage(imageRequest("seedream/5-pro-image-to-image", "edit", null, null, List.of(reference)));

        assertEquals("jpg", fileExtension(uploadBodies.getFirst().get("fileName")));
    }

    @Test
    void generateImageUploadDefaultsToPngWithoutAMimeType() {
        imageTask();
        var reference = new MediaReference(null, "aGVsbG8=");

        provider.generateImage(imageRequest("seedream/5-pro-image-to-image", "edit", null, null, List.of(reference)));

        assertEquals("png", fileExtension(uploadBodies.getFirst().get("fileName")));
    }

    @Test
    void generateImageFailsWhenTaskFails() {
        resultPath.set("/image.png");
        taskState.set("fail");
        failMsg.set("prompt contains prohibited content");

        var error = assertThrows(IllegalStateException.class,
                () -> provider.generateImage(imageRequest("seedream/5-pro-text-to-image", "a cafe", null, null, null)));

        assertTrue(error.getMessage().contains("prompt contains prohibited content"));
    }

    @Test
    void generateImageTimesOutWhileTaskIsStillRunning() {
        taskState.set("generating");
        provider.imagePollTimeout = Duration.ZERO;

        var error = assertThrows(IllegalStateException.class,
                () -> provider.generateImage(imageRequest("seedream/5-pro-text-to-image", "a cafe", null, null, null)));

        assertTrue(error.getMessage().contains("did not complete within"));
    }

    @Test
    void generateImageAppendsSeedreamHintToParameterError() {
        taskRejectMsg.set("aspect_ratio is not within the range of allowed options");

        var error = assertThrows(IllegalStateException.class,
                () -> provider.generateImage(imageRequest("seedream/5-pro-text-to-image", "a cafe", null, null, null)));

        assertTrue(error.getMessage().contains("Allowed parameters for seedream/5-pro-text-to-image"));
        assertTrue(error.getMessage().contains("quality (basic/high"));
    }

    private String fileExtension(Object fileName) {
        var value = String.valueOf(fileName);
        var dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1);
    }

    private void imageTask() {
        resultPath.set("/image.png");
        taskState.set("success");
    }

    private ImageGenerationRequest imageRequest(String model, String prompt, String size, String quality, List<MediaReference> inputImages) {
        return new ImageGenerationRequest(model, prompt, null, size, quality, null, null, null, inputImages, null, null, null);
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
