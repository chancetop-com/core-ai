package ai.core.tool.tools;

import ai.core.agent.AttachedContent;
import ai.core.agent.ExecutionContext;
import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class GenerateImageToolTest {
    @Test
    void buildDescriptionKeepsToolDocumentationAndAppendsConfiguredModels() {
        var description = GenerateImageTool.buildDescription(
                List.of(new MediaModelHint("seedream-5-pro", "seedream/5-pro-text-to-image", "KIE")));

        assertTrue(description.contains("prompt (required)"), "parameter documentation must be kept");
        assertTrue(description.contains("Configured image models"));
        assertTrue(description.contains("seedream-5-pro (KIE)"));
        assertTrue(description.contains("aspect_ratio"), "model-specific hint must be appended");
    }

    @Test
    void buildDescriptionTagsEachModelWithItsCapabilities() {
        var textToImage = new MediaModelHint("seedream-5-pro", "seedream/5-pro-text-to-image", "KIE");
        var imageToImage = new MediaModelHint("seedream-5-pro-edit", "seedream/5-pro-image-to-image", "KIE");
        var both = new MediaModelHint("gpt-image-2", "gpt-image-2", "OpenAI");

        var description = GenerateImageTool.buildDescription(List.of(textToImage, both), List.of(imageToImage, both));

        assertTrue(description.contains("seedream-5-pro (KIE) [text-to-image]"));
        assertTrue(description.contains("seedream-5-pro-edit (KIE) [image-to-image]"),
                "image.edits models must be listed so the agent can name one");
        assertTrue(description.contains("gpt-image-2 (OpenAI) [text-to-image, image-to-image]"));
        assertTrue(description.contains("IMAGE-TO-IMAGE"), "the editing rule must be in the description");
    }

    @Test
    void passesBase64InputImagesToMediaProvider() {
        var provider = new TestMediaProvider();
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        tool.execute(JSON.toJSON(Map.of(
                "prompt", "make it glass",
                "input_images", "[{\"b64Json\":\"data:image/png;base64,aGVsbG8=\"}]")), context);

        assertNotNull(provider.request.inputImages());
        assertEquals(1, provider.request.inputImages().size());
        assertEquals("data:image/png;base64,aGVsbG8=", provider.request.inputImages().getFirst().b64Json());
    }

    @Test
    void downloadsUrlInputImagesServerSideAndDropsTheUnreachableUrl() {
        var provider = new TestMediaProvider();
        var context = context(provider);
        var tool = GenerateImageTool.builder()
                .referenceImageLoader(url -> new GenerateVideoTool.ReferenceImageLoader.LoadedImage(
                        "hello".getBytes(StandardCharsets.UTF_8), "image/jpeg"))
                .build();

        tool.execute(JSON.toJSON(Map.of(
                "prompt", "make it glass",
                "input_images", "[\"https://example.com/image.jpg\"]")), context);

        var reference = provider.request.inputImages().getFirst();
        // KIE prefers url() over the inline data, and platform artifact URLs are unreachable from it
        assertNull(reference.url(), "the downloaded URL must not be forwarded upstream");
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)),
                reference.b64Json());
    }

    @Test
    void editsAttachedImagesWhenExplicitlyRequested() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder()
                .attachedContent(AttachedContent.ofBase64("aGVsbG8=", "image/jpeg", AttachedContent.AttachedContentType.IMAGE))
                .build();
        context.setImageMediaProvider(provider);
        var tool = GenerateImageTool.builder().build();

        tool.execute(JSON.toJSON(Map.of("prompt", "make it glass", "input_images", "attached")), context);

        assertEquals(1, provider.request.inputImages().size());
        assertEquals("data:image/jpeg;base64,aGVsbG8=", provider.request.inputImages().getFirst().b64Json());
    }

    @Test
    void downloadsAttachedImageUrls() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder()
                .attachedContent(AttachedContent.ofUrl("https://example.com/image.jpg", AttachedContent.AttachedContentType.IMAGE))
                .build();
        context.setImageMediaProvider(provider);
        var tool = GenerateImageTool.builder()
                .referenceImageLoader(url -> new GenerateVideoTool.ReferenceImageLoader.LoadedImage(
                        "hello".getBytes(StandardCharsets.UTF_8), "image/jpeg"))
                .build();

        tool.execute(JSON.toJSON(Map.of("prompt", "make it glass", "input_images", "attached")), context);

        var reference = provider.request.inputImages().getFirst();
        assertNull(reference.url());
        assertTrue(reference.b64Json().startsWith("data:image/jpeg;base64,"));
    }

    @Test
    void doesNotUseAttachedImagesUnlessRequested() {
        var provider = new TestMediaProvider();
        var context = ExecutionContext.builder()
                .attachedContent(AttachedContent.ofBase64("aGVsbG8=", "image/jpeg", AttachedContent.AttachedContentType.IMAGE))
                .build();
        context.setImageMediaProvider(provider);
        var tool = GenerateImageTool.builder().build();

        tool.execute(JSON.toJSON(Map.of("prompt", "a sunset")), context);

        assertNull(provider.request.inputImages(), "an attachment must not silently turn text-to-image into image-to-image");
    }

    @Test
    void failsWhenAttachedImagesAreRequestedButAbsent() {
        var provider = new TestMediaProvider();
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        var result = tool.execute(JSON.toJSON(Map.of("prompt", "make it glass", "input_images", "attached")), context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("no image is attached"));
    }

    @Test
    void passesMaskToMediaProvider() {
        var provider = new TestMediaProvider();
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        tool.execute(JSON.toJSON(Map.of(
                "prompt", "replace the sky",
                "mask", "data:image/png;base64,aGVsbG8=")), context);

        assertNotNull(provider.request.mask());
        assertEquals("data:image/png;base64,aGVsbG8=", provider.request.mask().b64Json());
    }

    @Test
    void leavesReferencesNullForPlainTextToImage() {
        var provider = new TestMediaProvider();
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        tool.execute(JSON.toJSON(Map.of("prompt", "a cafe")), context);

        assertNull(provider.request.inputImages());
        assertNull(provider.request.mask());
    }

    @Test
    void rejectsMalformedInputImages() {
        var provider = new TestMediaProvider();
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        var result = tool.execute(JSON.toJSON(Map.of("prompt", "a cafe", "input_images", "not json")), context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("input_images"));
    }

    private ExecutionContext context(MediaProvider provider) {
        var context = ExecutionContext.builder().build();
        context.setImageMediaProvider(provider);
        return context;
    }

    private static final class TestMediaProvider implements MediaProvider {
        private ImageGenerationRequest request;

        @Override
        public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
            this.request = request;
            return new ImageGenerationResponse(List.of(new ImageData(null, "https://cdn.example.com/out.png", null)), null);
        }

        @Override
        public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
            throw new UnsupportedOperationException();
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
