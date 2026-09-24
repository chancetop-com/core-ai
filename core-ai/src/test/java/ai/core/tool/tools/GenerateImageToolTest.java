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
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class GenerateImageToolTest {
    @Test
    void buildDescriptionKeepsToolDocumentationAndAppendsConfiguredModels() {
        var description = GenerateImageTool.buildDescription(
                List.of(new MediaModelHint("seedream-5-pro", "seedream/5-pro-text-to-image", "KIE")));

        assertTrue(description.startsWith("Generate one or more images from a text prompt"),
                "the text block must render without the incidental indentation of its class");
        assertTrue(description.contains("guesses wrong silently.\n\nFor gpt-image-2"),
                "the guidance and the parameter docs must stay separated by a blank line");
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
    void buildDescriptionKeepsImageEditsInTheToolInsteadOfHandWrittenCode() {
        var description = GenerateImageTool.buildDescription(
                List.of(new MediaModelHint("gpt-image-2.5", "gpt-image-2.5", "Azure")));

        assertTrue(description.contains("what it returns is the deliverable"),
                "the edit must be made by the tool, whose result is used as is");
        assertTrue(description.contains("Do NOT make it with image-processing code"),
                "hand-written pixel pipelines must be ruled out");
        assertTrue(description.contains("is a mask, not a code composite"),
                "a region-only edit must point at the mask parameter");
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
    void acceptsASingleReferenceObjectWithoutTheBrackets() {
        var provider = new TestMediaProvider();
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        tool.execute(JSON.toJSON(Map.of(
                "prompt", "make it glass",
                "input_images", "{\"b64Json\":\"data:image/png;base64,aGVsbG8=\"}")), context);

        assertNotNull(provider.request.inputImages(), "a missing pair of brackets must not fail the call");
        assertEquals("data:image/png;base64,aGVsbG8=", provider.request.inputImages().getFirst().b64Json());
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

    @Test
    void readsSandboxPathReferencesAndKeepsTheirName() throws Exception {
        var provider = new TestMediaProvider();
        var temp = Files.createTempFile("sandbox-ref-", ".jpg");
        Files.write(temp, "hello".getBytes(StandardCharsets.UTF_8));
        var sandbox = mock(ai.core.sandbox.Sandbox.class);
        when(sandbox.downloadFile("/tmp/fixed.jpg"))
                .thenReturn(new ai.core.sandbox.SandboxFile(temp, "fixed.jpg", "image/jpeg", 5));
        var context = ExecutionContext.builder().sandbox(sandbox).build();
        context.setImageMediaProvider(provider);
        var tool = GenerateImageTool.builder().build();

        tool.execute(JSON.toJSON(Map.of(
                "prompt", "use the fixed plate",
                "input_images", "[{\"sandbox_path\":\"/tmp/fixed.jpg\",\"name\":\"plate\",\"role\":\"subject\"}]")), context);

        var reference = provider.request.inputImages().getFirst();
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)),
                reference.b64Json());
        assertEquals("plate", reference.name());
    }

    @Test
    void readsLocalFileReferencesWithoutASandbox(@TempDir Path dir) throws Exception {
        var provider = new TestMediaProvider();
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();
        var file = dir.resolve("plate.png");
        Files.write(file, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        // a path a model writes into JSON escapes nothing when it uses the forward slashes the OS accepts
        var path = file.toString().replace('\\', '/');

        tool.execute(JSON.toJSON(Map.of(
                "prompt", "use the fixed plate",
                "input_images", "[{\"sandbox_path\":\"" + path + "\",\"name\":\"plate\"}]")), context);

        var reference = provider.request.inputImages().getFirst();
        assertTrue(reference.b64Json().startsWith("data:image/png;base64,"), reference.b64Json());
        assertEquals("plate", reference.name());
    }

    @Test
    void rejectsSandboxPathReferencesThatAreNotReadableLocally() {
        var provider = new TestMediaProvider();
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        var result = tool.execute(JSON.toJSON(Map.of(
                "prompt", "use the fixed plate",
                "input_images", "[{\"sandbox_path\":\"/tmp/fixed.jpg\"}]")), context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("no sandbox"), result.getResult());
    }

    @Test
    void explainsHowToRecoverWhenTheModelRejectsAReferenceImage() {
        var provider = new TestMediaProvider();
        provider.failure = new RuntimeException("OpenAI image request failed: HTTP 400: {\"error\": {\"message\": "
                + "\"Invalid image file or mode for image 2, please check your image file. If you believe this is an error,"
                + " contact us at Azure support ticket and include the request ID abc.\"}}");
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        var result = tool.execute(JSON.toJSON(Map.of(
                "prompt", "replace the food",
                "input_images", "[{\"b64Json\":\"data:image/jpeg;base64,aGVsbG8=\"},"
                        + "{\"b64Json\":\"data:image/jpeg;base64,aGVsbG8=\"}]")), context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("image 2"), result.getResult());
        assertTrue(result.getResult().contains("not a standard PNG/JPEG/WEBP"), result.getResult());
        assertTrue(result.getResult().contains("sandbox_path"), "the way back must be spelled out");
    }

    private ExecutionContext context(MediaProvider provider) {
        var context = ExecutionContext.builder().build();
        context.setImageMediaProvider(provider);
        return context;
    }

    private static final class TestMediaProvider implements MediaProvider {
        private ImageGenerationRequest request;
        private RuntimeException failure;

        @Override
        public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
            this.request = request;
            if (failure != null) throw failure;
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
