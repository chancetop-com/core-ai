package ai.core.server.evaluation.domain;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class EvalAttemptEntity {
    public String id;
    public String trialId;
    public Integer attemptNo;
    public String dispatchToken;
    public String status;
    public String agentRunId;
    public String detail;
    public ZonedDateTime createdAt;
    public ZonedDateTime updatedAt;
}
