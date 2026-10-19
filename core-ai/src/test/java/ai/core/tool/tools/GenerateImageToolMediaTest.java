package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.media.reference.ManagedReferenceProvider;
import com.sun.net.httpserver.HttpServer;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class GenerateImageToolMediaTest {
    private static final String PNG = "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

    @Test
    void failsInsteadOfDroppingReferencesOnEndpointsWithoutReferenceSupport() {
        var provider = new TestProvider(false);
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        var result = tool.execute(JSON.toJSON(Map.of("prompt", "make it glass", "input_images", "[{\"b64Json\":\"" + PNG + "\"}]")), context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("does not accept reference images"), result.getResult());
        assertNull(provider.request, "the provider must not receive a request it would silently ignore references on");
    }

    @Test
    void usesTheEditDefaultModelWhenReferencesArePresent() {
        var provider = new TestProvider(true);
        var context = context(provider);
        context.getCustomVariables().put("media.image.model", "text-model");
        context.getCustomVariables().put("media.image.edit.models", List.of("edit-model"));
        context.getCustomVariables().put("media.image.edit.model", "edit-model");
        var tool = GenerateImageTool.builder().build();

        tool.execute(JSON.toJSON(Map.of("prompt", "make it glass", "input_images", "[{\"b64Json\":\"" + PNG + "\"}]")), context);

        assertEquals("edit-model", provider.request.model());
    }

    @Test
    void keepsAsessionModelThatCanEdit() {
        var provider = new TestProvider(true);
        var context = context(provider);
        context.getCustomVariables().put("media.image.model", "both-model");
        context.getCustomVariables().put("media.image.edit.models", List.of("both-model", "edit-model"));
        context.getCustomVariables().put("media.image.edit.model", "edit-model");
        var tool = GenerateImageTool.builder().build();

        tool.execute(JSON.toJSON(Map.of("prompt", "make it glass", "input_images", "[{\"b64Json\":\"" + PNG + "\"}]")), context);

        assertEquals("both-model", provider.request.model());
    }

    @Test
    void fallsBackToTheCatalogDefaultWhenTheSessionModelWasCleared() {
        var provider = new TestProvider(true);
        var context = context(provider);
        context.getCustomVariables().put("media.image.default.model", "catalog-default");
        var tool = GenerateImageTool.builder().build();

        tool.execute(JSON.toJSON(Map.of("prompt", "a cafe")), context);

        assertEquals("catalog-default", provider.request.model());
    }

    @Test
    void passesSymbolicReferencesThroughToAManagedProvider() {
        var provider = new ManagedTestProvider(true);
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        tool.execute(JSON.toJSON(Map.of(
                "prompt", "same cat, seaside",
                "input_images", "[{\"media_id\":\"gateway-media-v1.img.abc\",\"name\":\"cat\"}]")), context);

        assertEquals("gateway-media-v1.img.abc", provider.lastRequest().inputImages().getFirst().mediaId());
        assertNull(provider.lastRequest().inputImages().getFirst().b64Json(), "a managed provider resolves the handle server-side");
    }

    @Test
    void downloadsUrlResultsIntoTheSink() throws IOException {
        var server = imageServer();
        try {
            var provider = new TestProvider(true);
            var imageUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/out.png";
            provider.images = List.of(new ImageData(null, imageUrl, null));
            var context = context(provider);
            var sink = new RecordingSink();
            context.getCustomVariables().put(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY, sink);
            var tool = GenerateImageTool.builder().build();

            var result = tool.execute(JSON.toJSON(Map.of("prompt", "a cafe")), context);

            assertTrue(result.getResult().contains(sink.path), result.getResult());
            assertEquals(1, sink.files.size());
            assertEquals("image/png", sink.files.getFirst().contentType());
            assertEquals(3, sink.files.getFirst().bytes.length);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void savesEveryImageOfAMultipleImageResult() {
        var provider = new TestProvider(true);
        provider.images = List.of(
                new ImageData("aW1n", null, null),
                new ImageData("aW1n", null, null));
        var context = context(provider);
        var sink = new RecordingSink();
        context.getCustomVariables().put(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY, sink);
        var tool = GenerateImageTool.builder().build();

        var result = tool.execute(JSON.toJSON(Map.of("prompt", "a cafe", "n", 2)), context);

        assertEquals(2, sink.files.size());
        assertTrue(result.getResult().contains("Generated 2 image(s)"), result.getResult());
    }

    @Test
    void leavesUrlResultsAloneWithoutASink() {
        var provider = new TestProvider(true);
        provider.images = List.of(new ImageData(null, "https://cdn.example.com/out.png", null));
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        var result = tool.execute(JSON.toJSON(Map.of("prompt", "a cafe")), context);

        assertTrue(result.getResult().contains("https://cdn.example.com/out.png"));
    }

    @Test
    void requiresMediaIdReferencesToUseAManagedProvider() {
        var provider = new TestProvider(false);
        var context = context(provider);
        var tool = GenerateImageTool.builder().build();

        var result = tool.execute(JSON.toJSON(Map.of(
                "prompt", "same cat",
                "input_images", "[{\"media_id\":\"gateway-media-v1.img.abc\"}]")), context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("media_id"), result.getResult());
    }

    private ExecutionContext context(MediaProvider provider) {
        var context = ExecutionContext.builder().build();
        context.setImageMediaProvider(provider);
        return context;
    }

    private HttpServer imageServer() throws IOException {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/out.png", exchange -> {
            var bytes = new byte[]{1, 2, 3};
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static class TestProvider implements MediaProvider {
        private final boolean acceptsReferences;
        private ImageGenerationRequest request;
        private List<ImageData> images = List.of(new ImageData(null, "https://cdn.example.com/out.png", null));

        private TestProvider(boolean acceptsReferences) {
            this.acceptsReferences = acceptsReferences;
        }

        ImageGenerationRequest lastRequest() {
            return request;
        }

        @Override
        public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
            this.request = request;
            return new ImageGenerationResponse(images, null);
        }

        @Override
        public boolean acceptsImageReferences() {
            return acceptsReferences;
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

    private static final class ManagedTestProvider extends TestProvider implements ManagedReferenceProvider {
        private ManagedTestProvider(boolean acceptsReferences) {
            super(acceptsReferences);
        }
    }

    private static final class RecordingSink implements GenerateImageTool.ImageOutputSink {
        private final List<SavedFile> files = new ArrayList<>();
        private String path;

        @Override
        public String save(String fileName, String contentType, byte[] bytes) {
            path = "/workspace/.core-ai/media/images/" + fileName;
            files.add(new SavedFile(contentType, bytes));
            return path;
        }

        private record SavedFile(String contentType, byte[] bytes) {
        }
    }
}
