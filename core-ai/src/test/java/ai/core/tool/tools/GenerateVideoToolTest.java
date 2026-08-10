package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class GenerateVideoToolTest {
    @Test
    void usesAttachedImagesWhenReferencesAreNotExplicitlyProvided() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder()
                .attachedContent(ExecutionContext.AttachedContent.ofBase64(
                        "aGVsbG8=", "image/jpeg", ExecutionContext.AttachedContent.AttachedContentType.IMAGE))
                .build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder().build();

        tool.execute(JSON.toJSON(Map.of("prompt", "Animate the attached image")), context);

        assertNotNull(provider.videoRequest);
        assertEquals("data:image/jpeg;base64,aGVsbG8=", provider.videoRequest.inputReferences().getFirst().b64Json());
    }

    @Test
    void passesPreviousVideoIdToMediaProvider() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder().build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder().build();

        tool.execute(JSON.toJSON(Map.of(
                "prompt", "Make the violin invisible",
                "previous_video_id", "gateway-video-v1.previous")), context);

        assertNotNull(provider.videoRequest);
        assertEquals("gateway-video-v1.previous", provider.videoRequest.previousInteractionId());
    }

    @Test
    void passesBase64ReferenceImagesToMediaProvider() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder().build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder().build();

        tool.execute(JSON.toJSON(Map.of(
                "prompt", "Animate the reference image",
                "input_references", "[{\"b64Json\":\"data:image/jpeg;base64,aGVsbG8=\"}]")), context);

        assertNotNull(provider.videoRequest);
        assertEquals(1, provider.videoRequest.inputReferences().size());
        assertEquals("data:image/jpeg;base64,aGVsbG8=", provider.videoRequest.inputReferences().getFirst().b64Json());
    }

    @Test
    void downloadsUrlReferencesServerSide() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder().build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder()
                .referenceImageLoader(url -> new GenerateVideoTool.ReferenceImageLoader.LoadedImage(
                        "aGVsbG8=".getBytes(StandardCharsets.UTF_8), "image/jpeg"))
                .build();

        tool.execute(JSON.toJSON(Map.of(
                "prompt", "Animate the reference image",
                "input_references", "[{\"url\":\"https://example.com/image.jpg\"}]")), context);

        assertNotNull(provider.videoRequest);
        var expected = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString("aGVsbG8=".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, provider.videoRequest.inputReferences().getFirst().b64Json());
    }

    @Test
    void rejectsSecondSubmissionWhilePreviousTaskIsPending() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder().build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder().build();

        var first = tool.execute(JSON.toJSON(Map.of("prompt", "First video")), context);
        assertTrue(first.isPending(), "first submission should be pending");
        assertEquals("video-id", first.getTaskId());

        var second = tool.execute(JSON.toJSON(Map.of("prompt", "Second video")), context);
        assertTrue(second.isFailed(), "second submission while previous task is pending must be rejected");
        assertTrue(second.getResult().contains("video-id"), "rejection should name the in-flight task");
    }

    @Test
    void allowsNewSubmissionAfterPreviousTaskReachesTerminalStatus() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder().build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder().build();
        var statusTool = GetVideoStatusTool.builder().build();

        var first = tool.execute(JSON.toJSON(Map.of("prompt", "First video")), context);
        assertEquals("video-id", first.getTaskId());

        provider.status = "completed";
        statusTool.execute(JSON.toJSON(Map.of("video_id", "video-id")), context);

        var second = tool.execute(JSON.toJSON(Map.of("prompt", "Second video")), context);
        assertTrue(second.isPending(), "submission after terminal status should be allowed");
        assertEquals("video-id", second.getTaskId());
    }

    @Test
    void failedStatusClearsPendingTask() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder().build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder().build();
        var statusTool = GetVideoStatusTool.builder().build();

        var first = tool.execute(JSON.toJSON(Map.of("prompt", "First video")), context);
        assertEquals("video-id", first.getTaskId());

        provider.status = "failed";
        provider.error = "upstream exploded";
        var status = statusTool.execute(JSON.toJSON(Map.of("video_id", "video-id")), context);
        assertTrue(status.isFailed());
        assertTrue(status.getResult().contains("upstream exploded"), "provider error must be surfaced");

        var second = tool.execute(JSON.toJSON(Map.of("prompt", "Second video")), context);
        assertTrue(second.isPending(), "submission after failed terminal status should be allowed");
    }

    @Test
    void failureMessageGuidesRetryAfterRepeatedFailures() {
        var provider = new FailingMediaProvider();
        var context = ExecutionContext.builder().build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder().build();

        var first = tool.execute(JSON.toJSON(Map.of("prompt", "boom")), context);
        var second = tool.execute(JSON.toJSON(Map.of("prompt", "boom")), context);

        assertTrue(first.isFailed());
        assertTrue(second.isFailed());
        assertTrue(second.getResult().contains("Do NOT keep guessing"), "repeated failures must inject guidance");
    }

    @Test
    void sessionScopeSetsConversationDefaultModel() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder().build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder().build();

        tool.execute(JSON.toJSON(Map.of("prompt", "A cat", "model", "kling-2.6", "model_scope", "session")), context);

        assertEquals("kling-2.6", context.getCustomVariables().get("media.video.model"));
        assertEquals("kling-2.6", provider.videoRequest.model(), "session scope must also apply to the current call");
    }

    @Test
    void sessionScopeWithEmptyModelClearsConversationDefault() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder().customVariable("media.video.model", "old-model").build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder().build();

        tool.execute(JSON.toJSON(Map.of("prompt", "A cat", "model", "", "model_scope", "session")), context);

        assertFalse(context.getCustomVariables().containsKey("media.video.model"), "session default must be cleared");
    }

    @Test
    void onceScopeDoesNotChangeConversationDefault() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder().build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder().build();

        tool.execute(JSON.toJSON(Map.of("prompt", "A cat", "model", "kling-2.6")), context);

        assertFalse(context.getCustomVariables().containsKey("media.video.model"), "once scope must not change the session default");
    }

    @Test
    void failedGenerationDoesNotChangeConversationDefault() {
        var provider = new FailingMediaProvider();
        var context = ExecutionContext.builder().build();
        context.setVideoMediaProvider(provider);
        var tool = GenerateVideoTool.builder().build();

        tool.execute(JSON.toJSON(Map.of("prompt", "boom", "model", "kling-2.6", "model_scope", "session")), context);

        assertFalse(context.getCustomVariables().containsKey("media.video.model"), "failed generation must not set the session default");
    }

    private static final class TestMediaProvider implements MediaProvider {
        private VideoGenerationRequest videoRequest;
        private String status = "processing";
        private String error;

        @Override
        public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
            videoRequest = request;
            return new VideoGenerationResponse("video-id", "processing", null, null);
        }

        @Override
        public VideoStatusResponse getVideoStatus(String videoId) {
            return new VideoStatusResponse(videoId, status, null, error, null);
        }

        @Override
        public byte[] downloadVideo(String videoId) {
            return new byte[0];
        }
    }

    private static final class FailingMediaProvider implements MediaProvider {
        @Override
        public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
            throw new IllegalArgumentException("upstream rejected the request: invalid reference format");
        }

        @Override
        public VideoStatusResponse getVideoStatus(String videoId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] downloadVideo(String videoId) {
            throw new UnsupportedOperationException();
        }
    }
}
