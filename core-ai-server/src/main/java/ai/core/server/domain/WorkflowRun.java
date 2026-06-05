package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * One execution of a published workflow version: the durable cursor plus the single cross-replica claim
 * (lease). Mirrors AgentRun (status) + AgentSchedule.nextRunAt (the CAS-on-a-timestamp lease). The
 * authoritative per-node facts live in WorkflowNodeRun; this document holds only run-level state and the
 * lease. There is deliberately no edge_state cache here — the planner re-derives it from node-runs.
 *
 * @author Xander
 */
@Collection(name = "workflow_runs")
public class WorkflowRun {
    @Id
    public String id;

    @NotNull
    @Field(name = "workflow_id")
    public String workflowId;

    @Field(name = "version_id")
    public String versionId;

    @Field(name = "version_sha256")
    public String versionSha256;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @Field(name = "mode")
    public WorkflowMode mode;

    @Field(name = "session_id")
    public String sessionId;

    @Field(name = "triggered_by")
    public TriggerType triggeredBy;

    @NotNull
    @Field(name = "status")
    public RunStatus status;

    @Field(name = "input")
    public String input;

    @Field(name = "output")
    public String output;

    @Field(name = "token_usage")
    public TokenUsage tokenUsage;

    @Field(name = "error")
    public String error;

    // ---- run-level CAS lease: the only cross-replica claim (mirrors AgentSchedule.nextRunAt) ----
    @Field(name = "claimed_by")
    public String claimedBy;

    @Field(name = "lease_until")
    public ZonedDateTime leaseUntil;

    @Field(name = "started_at")
    public ZonedDateTime startedAt;

    @Field(name = "completed_at")
    public ZonedDateTime completedAt;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
