package ai.core.media.domain;

import java.util.List;

/**
 * @author stephen
 */
public record VideoGenerationRequest(
        String model,
        String prompt,
        Integer seconds,
        String size,
        List<MediaReference> inputReferences,
        String providerExtra) {
}
