package ai.core.media.domain;

import java.util.List;

/**
 * @author stephen
 * @param mediaId gateway handle for referencing this generation in a later call, null off-gateway
 * @param notes non-fatal reference-compilation notes (trimmed references, unmentioned names)
 */
public record ImageGenerationResponse(List<ImageData> data, Usage usage, String interactionId,
                                      String mediaId, List<String> notes) {
    public ImageGenerationResponse(List<ImageData> data, Usage usage) {
        this(data, usage, null, null, List.of());
    }

    public ImageGenerationResponse(List<ImageData> data, Usage usage, String interactionId) {
        this(data, usage, interactionId, null, List.of());
    }

    public ImageGenerationResponse {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public ImageGenerationResponse with(String handle, List<String> compilationNotes) {
        return new ImageGenerationResponse(data, usage, interactionId, handle, compilationNotes);
    }
}
