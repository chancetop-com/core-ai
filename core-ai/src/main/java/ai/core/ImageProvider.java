package ai.core;

import ai.core.providers.inner.GenerateImageRequest;
import ai.core.providers.inner.GenerateImageResponse;

/**
 * @author stephen
 */
public interface ImageProvider {
    GenerateImageResponse generateImage(GenerateImageRequest request);
}
