package ai.core.media;

import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;

/**
 * @author Stephen
 */
public class VertexGeminiImageMediaProvider implements MediaProvider {
    private final String baseUrl;
    private final GoogleAccessTokenProvider accessTokenProvider;

    public VertexGeminiImageMediaProvider(String baseUrl, String projectId, String location, GoogleAccessTokenProvider accessTokenProvider) {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("Vertex project ID is required");
        if (location == null || location.isBlank()) throw new IllegalArgumentException("Vertex location is required");
        this.baseUrl = endpoint(baseUrl, projectId, location);
        this.accessTokenProvider = accessTokenProvider;
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        return new GeminiImageMediaProvider(baseUrl, "Bearer " + accessTokenProvider.accessToken(), "Authorization").generateImage(request);
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        throw new UnsupportedOperationException("Vertex Gemini generateContent protocol does not support video generation");
    }

    @Override
    public VideoStatusResponse getVideoStatus(String videoId) {
        throw new UnsupportedOperationException("Vertex Gemini generateContent protocol does not support video generation");
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        throw new UnsupportedOperationException("Vertex Gemini generateContent protocol does not support video generation");
    }

    private String endpoint(String configuredBaseUrl, String projectId, String location) {
        var root = configuredBaseUrl == null || configuredBaseUrl.isBlank()
                ? "https://" + location + "-aiplatform.googleapis.com/v1"
                : configuredBaseUrl.replaceAll("/+$", "");
        return root + "/projects/" + projectId + "/locations/" + location + "/publishers/google";
    }
}
