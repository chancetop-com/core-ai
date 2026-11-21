package ai.core.server.evaluation.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgTrialRow {
    @Column(name = "id")
    public String id;
    @Column(name = "run_id")
    public String runId;
    @Column(name = "case_id")
    public String caseId;
    @Column(name = "case_hash")
    public String caseHash;
    @Column(name = "repetition_index")
    public Integer repetitionIndex;
    @Column(name = "split")
    public String split;
    @Column(name = "weight")
    public Double weight;
    @Column(name = "status")
    public String status;
    @Column(name = "execution_result")
    public String executionResult;
    @Column(name = "task_result")
    public String taskResult;
    @Column(name = "agent_run_id")
    public String agentRunId;
    @Column(name = "owner")
    public String owner;
    @Column(name = "generation")
    public Integer generation;
    @Column(name = "lease_until")
    public ZonedDateTime leaseUntil;
    @Column(name = "dispatch_token")
    public String dispatchToken;
    @Column(name = "actual_output")
    public String actualOutput;
    @Column(name = "trace_json")
    public String traceJson;
    @Column(name = "artifacts_json")
    public String artifactsJson;
    @Column(name = "usage_json")
    public String usageJson;
    @Column(name = "grading_json")
    public String gradingJson;
    @Column(name = "error")
    public String error;
    @Column(name = "stop_requested_at")
    public ZonedDateTime stopRequestedAt;
    @Column(name = "stop_confirmed_at")
    public ZonedDateTime stopConfirmedAt;
    @Column(name = "started_at")
    public ZonedDateTime startedAt;
    @Column(name = "completed_at")
    public ZonedDateTime completedAt;
    @Column(name = "created_at")
    public ZonedDateTime createdAt;
    @Column(name = "data")
    public String data;
}
