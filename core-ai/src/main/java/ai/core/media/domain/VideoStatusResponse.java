package ai.core.media.domain;

/**
 * @author stephen
 */
public record VideoStatusResponse(String id, String status, Integer progress, String error, Long completedAt) {
}
