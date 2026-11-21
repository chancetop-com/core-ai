package ai.core.server.evaluation.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgAttemptRow {
    @Column(name = "id")
    public String id;
    @Column(name = "trial_id")
    public String trialId;
    @Column(name = "attempt_no")
    public Integer attemptNo;
    @Column(name = "dispatch_token")
    public String dispatchToken;
    @Column(name = "status")
    public String status;
    @Column(name = "agent_run_id")
    public String agentRunId;
    @Column(name = "detail")
    public String detail;
    @Column(name = "created_at")
    public ZonedDateTime createdAt;
    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
