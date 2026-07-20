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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    private static final class TestMediaProvider implements MediaProvider {
        private VideoGenerationRequest videoRequest;

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
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] downloadVideo(String videoId) {
            throw new UnsupportedOperationException();
        }
    }
}
