package ai.core.media.domain;

/**
 * @author stephen
 */
public record VideoStatusResponse(String id, String status, Integer progress, String error, Long completedAt,
                                  Double creditsConsumed, Double upstreamCostUsd) {
    public VideoStatusResponse(String id, String status, Integer progress, String error, Long completedAt) {
        this(id, status, progress, error, completedAt, null, null);
    }
}
