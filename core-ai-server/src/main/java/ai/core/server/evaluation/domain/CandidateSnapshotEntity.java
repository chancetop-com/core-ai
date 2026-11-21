package ai.core.server.evaluation.domain;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class CandidateSnapshotEntity {
    public String id;
    public String evaluationId;
    public String agentId;
    public String source;
    public String sourceRevision;
    public String fingerprint;
    public String snapshotJson;
    public String createdBy;
    public ZonedDateTime createdAt;
}
