package ai.core.tool.tools;

import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.tool.github.GitHubTokenProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticBridgeMigrationTest {
    @Test
    void generateVideoPollUsesItsConfiguredProvider() {
        var provider = new TestMediaProvider();
        var tool = GenerateVideoTool.builder(provider).build();

        var result = tool.poll("video-1");

        assertTrue(result.isCompleted());
        assertEquals("video-1", provider.lastVideoId);
    }

    @Test
    void githubTokenToolUsesItsConfiguredProvider() {
        var provider = new RecordingGitHubTokenProvider();
        var tool = RequireGithubInstallationTokenTool.builder(provider).build();

        var result = tool.execute("{\"repo\":\"owner/repo\"}");

        assertTrue(result.isCompleted());
        assertEquals("owner/repo", provider.repo);
    }

    private static final class RecordingGitHubTokenProvider implements GitHubTokenProvider {
        private String repo;

        @Override
        public String getInstallationToken(String repo) {
            this.repo = repo;
            return "explicit-token";
        }
    }

    private static final class TestMediaProvider implements MediaProvider {
        private String lastVideoId;

        @Override
        public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VideoStatusResponse getVideoStatus(String videoId) {
            lastVideoId = videoId;
            return new VideoStatusResponse(videoId, "completed", null, null, null);
        }

        @Override
        public byte[] downloadVideo(String videoId) {
            throw new UnsupportedOperationException();
        }
    }
}
