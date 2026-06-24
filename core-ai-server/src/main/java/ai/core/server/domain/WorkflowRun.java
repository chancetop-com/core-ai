package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

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

    // True for draft-preview ("Test") runs, whose pinned version is a throwaway preview snapshot. A TTL co-expires
    // these runs with that 1-day version snapshot so run history never shows a run whose graph snapshot is gone.
    @Field(name = "preview")
    public Boolean preview;

    // ---- resume-from-node lineage: set only when this run was seeded from a source run's frozen prefix ----
    @Field(name = "resumed_from_run_id")
    public String resumedFromRunId;

    @Field(name = "resume_from_node_id")
    public String resumeFromNodeId;

    // ---- Sub-workflow lineage (WORKFLOW node): set when this run is a child started by a parent run's WORKFLOW node ----
    @Field(name = "parent_run_id")
    public String parentRunId;

    @Field(name = "parent_node_id")
    public String parentNodeId;

    // nesting depth from the top-level run (top-level = 0). The runtime recursion backstop caps this.
    @Field(name = "depth")
    public Integer depth;

    @NotNull
    @Field(name = "status")
    public RunStatus status;

    @Field(name = "input")
    public String input;

    @Field(name = "output")
    public String output;

    // Files the run delivers to the caller: the union (by file_id) of every COMPLETED node-run's artifacts.
    @Field(name = "artifacts")
    public List<ArtifactRef> artifacts;

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
