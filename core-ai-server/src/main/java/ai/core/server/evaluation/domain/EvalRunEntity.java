package ai.core.server.evaluation.domain;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class EvalRunEntity {
    public String id;
    public String evaluationId;
    public String actorId;
    public String requestKey;
    public String requestHash;
    public String specId;
    public String snapshotId;
    public EvalEnums.Purpose purpose;
    public String experimentLabel;
    public EvalEnums.RunStatus status;
    public Integer plannedTrials;
    public Integer startedTrials;
    public Integer succeededTrials;
    public Integer failedTrials;
    public Integer unmeasuredTrials;
    public String cancelRequestedBy;
    public ZonedDateTime cancelRequestedAt;
    public ZonedDateTime deadlineAt;
    public String budgetJson;
    public String reportJson;
    public String error;
    public ZonedDateTime createdAt;
    public ZonedDateTime startedAt;
    public ZonedDateTime completedAt;
}
