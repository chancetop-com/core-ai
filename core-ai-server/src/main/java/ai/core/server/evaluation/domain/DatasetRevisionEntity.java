package ai.core.server.evaluation.domain;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class DatasetRevisionEntity {
    public String id;
    public String evaluationId;
    public Integer revision;
    public String status;
    public String manifestJson;
    public String contentHash;
    public Integer caseCount;
    public String createdBy;
    public ZonedDateTime createdAt;
}
