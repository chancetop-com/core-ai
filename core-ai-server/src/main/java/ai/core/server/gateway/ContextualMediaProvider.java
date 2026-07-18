package ai.core.server.gateway;

import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;

/**
 * @author Stephen
 */
public final class ContextualMediaProvider implements MediaProvider {
    private final GatewayMediaProvider delegate;
    private final MediaJobOwner owner;

    public ContextualMediaProvider(GatewayMediaProvider delegate, MediaJobOwner owner) {
        this.delegate = delegate;
        this.owner = owner;
    }

    @Override
    public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        return delegate.generateImage(request);
    }

    @Override
    public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        return delegate.generateVideo(request, owner);
    }

    @Override
    public VideoStatusResponse getVideoStatus(String videoId) {
        return delegate.getVideoStatus(videoId);
    }

    @Override
    public byte[] downloadVideo(String videoId) {
        return delegate.downloadVideo(videoId);
    }
}
