package ai.core.media.domain;

import java.util.List;

/**
 * @author stephen
 * @param notes non-fatal reference-compilation notes (trimmed references, unmentioned names)
 */
public record VideoGenerationResponse(String id, String status, Long createdAt, Usage usage, List<String> notes) {
    public VideoGenerationResponse(String id, String status, Long createdAt, Usage usage) {
        this(id, status, createdAt, usage, List.of());
    }

    public VideoGenerationResponse {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
