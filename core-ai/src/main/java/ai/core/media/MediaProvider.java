package ai.core.media;

import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;

/**
 * @author stephen
 */
public interface MediaProvider {
    ImageGenerationResponse generateImage(ImageGenerationRequest request);

    VideoGenerationResponse generateVideo(VideoGenerationRequest request);

    VideoStatusResponse getVideoStatus(String videoId);

    byte[] downloadVideo(String videoId);
}
