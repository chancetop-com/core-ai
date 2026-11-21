package ai.core.server.evaluation.domain;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class EvalGradingEntity {
    public String id;
    public String trialId;
    public String graderSuiteId;
    public String outputHash;
    public Integer generation;
    public EvalEnums.GradingStatus status;
    public String resultsJson;
    public String error;
    public ZonedDateTime createdAt;
    public ZonedDateTime completedAt;
}
