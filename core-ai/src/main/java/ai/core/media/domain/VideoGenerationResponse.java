package ai.core.media.domain;

/**
 * @author stephen
 */
public record VideoGenerationResponse(String id, String status, Long createdAt, Usage usage) {
}
