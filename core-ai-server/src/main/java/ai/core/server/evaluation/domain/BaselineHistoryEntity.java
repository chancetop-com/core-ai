package ai.core.server.evaluation.domain;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class BaselineHistoryEntity {
    public String id;
    public String evaluationId;
    public String baselineRunId;
    public String previousRunId;
    public Integer generation;
    public String reason;
    public String actorId;
    public ZonedDateTime createdAt;
}
