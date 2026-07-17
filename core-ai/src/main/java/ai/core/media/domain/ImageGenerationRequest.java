package ai.core.media.domain;

import java.util.List;

/**
 * @author stephen
 */
public record ImageGenerationRequest(
        String model,
        String prompt,
        Integer n,
        String size,
        String quality,
        String outputFormat,
        Integer outputCompression,
        String background,
        List<MediaReference> inputImages,
        MediaReference mask,
        String providerExtra) {
}
