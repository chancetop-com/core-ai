package ai.core.media.domain;

import java.util.List;

/**
 * @author stephen
 */
public record ImageGenerationResponse(List<ImageData> data, Usage usage) {
}
