package ai.core.image;

import ai.core.image.providers.inner.GenerateImageRequest;
import ai.core.image.providers.inner.GenerateImageResponse;

/**
 * @author stephen
 */
public interface ImageProvider {
    GenerateImageResponse generateImage(GenerateImageRequest request);
}
