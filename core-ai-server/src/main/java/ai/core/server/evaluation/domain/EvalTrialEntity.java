package ai.core.server.evaluation.domain;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class EvalTrialEntity {
    public String id;
    public String runId;
    public String caseId;
    public String caseHash;
    public Integer repetitionIndex;
    public String split;
    public Double weight;
    public EvalEnums.TrialStatus status;
    public EvalEnums.ExecutionResult executionResult;
    public EvalEnums.TaskResult taskResult;
    public String agentRunId;
    public String owner;
    public Integer generation;
    public ZonedDateTime leaseUntil;
    public String dispatchToken;
    public String actualOutput;
    public String traceJson;
    public String artifactsJson;
    public String usageJson;
    public String gradingJson;
    public String error;
    public ZonedDateTime stopRequestedAt;
    public ZonedDateTime stopConfirmedAt;
    public ZonedDateTime startedAt;
    public ZonedDateTime completedAt;
    public ZonedDateTime createdAt;
}
