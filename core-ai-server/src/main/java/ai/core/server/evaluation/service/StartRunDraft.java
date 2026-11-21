package ai.core.server.evaluation.service;

/**
 * @author stephen
 */
public record StartRunDraft(String specId,
                            String source,
                            String snapshotId,
                            String purpose,
                            String experimentLabel,
                            String requestKey) {
}
