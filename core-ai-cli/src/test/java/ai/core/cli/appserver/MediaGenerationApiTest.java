package ai.core.cli.appserver;

import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaGenerationApiTest {
    @TempDir
    Path workspace;

    @Test
    void imageGenerationWritesTheDecodedFileIntoTheMediaRoot() throws Exception {
        var bytes = new byte[]{1, 2, 3, 4};
        var api = new MediaGenerationApi(workspace, new FakeMediaProvider(bytes, "completed"),
                null, "image-model", null, null);

        var params = JsonNodeFactory.instance.objectNode();
        params.put("kind", "image");
        params.put("prompt", "a test image");
        var result = api.generate(params);

        assertThat(result.path("kind").asText()).isEqualTo("image");
        var file = result.path("files").get(0);
        assertThat(file.path("path").asText()).startsWith(".core-ai/media/images/");
        assertThat(file.path("contentType").asText()).isEqualTo("image/png");
        assertThat(file.path("size").asLong()).isEqualTo(bytes.length);
        assertThat(Files.readAllBytes(workspace.resolve(file.path("path").asText()))).isEqualTo(bytes);
    }

    @Test
    void videoJobsPollUntilCompletedAndSaveTheDownload() throws Exception {
        var api = new MediaGenerationApi(workspace, null, new FakeMediaProvider(new byte[]{9, 8, 7}, "completed"),
                null, "video-model", null);

        var params = JsonNodeFactory.instance.objectNode();
        params.put("kind", "video");
        params.put("prompt", "a test video");
        var submitted = api.generate(params);
        var id = submitted.path("job").path("id").asText();
        assertThat(id).isEqualTo("video-1");
        assertThat(submitted.path("job").path("status").asText()).isEqualTo("pending");

        var jobParams = JsonNodeFactory.instance.objectNode();
        jobParams.put("id", id);
        var refreshed = api.job(jobParams);
        assertThat(refreshed.path("status").asText()).isEqualTo("completed");
        assertThat(refreshed.path("path").asText()).startsWith(".core-ai/media/videos/");
        assertThat(Files.readAllBytes(workspace.resolve(refreshed.path("path").asText()))).isEqualTo(new byte[]{9, 8, 7});
    }

    @Test
    void missingProviderAndPromptAreRejected() {
        var api = new MediaGenerationApi(workspace, null, null, null, null, null);
        var params = JsonNodeFactory.instance.objectNode();
        params.put("kind", "image");
        params.put("prompt", "x");
        assertThatThrownBy(() -> api.generate(params))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("no image media provider");
    }

    @Test
    void videoJobsAreScopedToTheOwningPage() {
        var api = new MediaGenerationApi(workspace, null, new FakeMediaProvider(new byte[]{1}, "pending"),
                null, "video-model", null);
        var params = JsonNodeFactory.instance.objectNode();
        params.put("kind", "video");
        params.put("prompt", "a test video");
        params.put("_owner", "plugin.a/page");
        var id = api.generate(params).path("job").path("id").asText();

        var own = JsonNodeFactory.instance.objectNode();
        own.put("_owner", "plugin.a/page");
        assertThat(api.jobs(own).path("jobs")).hasSize(1);

        var other = JsonNodeFactory.instance.objectNode();
        other.put("_owner", "plugin.b/page");
        assertThat(api.jobs(other).path("jobs")).isEmpty();

        var jobParams = JsonNodeFactory.instance.objectNode();
        jobParams.put("id", id);
        jobParams.put("_owner", "plugin.b/page");
        assertThatThrownBy(() -> api.job(jobParams))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("not known");
    }

    private static final class FakeMediaProvider implements MediaProvider {
        private final byte[] bytes;
        private final String videoStatus;

        private FakeMediaProvider(byte[] bytes, String videoStatus) {
            this.bytes = bytes;
            this.videoStatus = videoStatus;
        }

        @Override
        public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
            return new ImageGenerationResponse(List.of(new ImageData(Base64.getEncoder().encodeToString(bytes), null, null)), null);
        }

        @Override
        public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
            return new VideoGenerationResponse("video-1", "pending", 1L, null);
        }

        @Override
        public VideoStatusResponse getVideoStatus(String videoId) {
            return new VideoStatusResponse(videoId, videoStatus, 100, null, 2L);
        }

        @Override
        public byte[] downloadVideo(String videoId) {
            return bytes;
        }
    }
}
