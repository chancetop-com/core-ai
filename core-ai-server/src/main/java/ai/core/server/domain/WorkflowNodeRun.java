package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * A node execution at one scope: the engine's only durable per-node fact, and a checkpoint for value reuse,
 * recovery and observability. Identity is (run_id, node_id, scope_path_key), so a container sub-graph node
 * executed N times yields N distinct records; the race-safe insert relies on the unique index over those
 * three fields (same idiom as spans.span_id — see SchemaMigrationVWorkflowIndexes).
 *
 * <p>Control flow is carried by {@code chosen_edge_ids} alone: null means NORMAL (every out-edge active),
 * non-null means BRANCH (only those edges active). The planner derives all edge verdicts from this; edge
 * tri-state is never stored.
 *
 * @author Xander
 */
@Collection(name = "workflow_node_runs")
public class WorkflowNodeRun {
    @Id
    public String id;

    @NotNull
    @Field(name = "run_id")
    public String runId;

    @Field(name = "workflow_id")
    public String workflowId;

    @NotNull
    @Field(name = "node_id")
    public String nodeId;

    @Field(name = "node_type")
    public String nodeType;

    @Field(name = "scope_path")
    public List<ScopeFrame> scopePath;

    @NotNull
    @Field(name = "scope_path_key")
    public String scopePathKey;

    @NotNull
    @Field(name = "status")
    public NodeRunStatus status;

    @Field(name = "input_json")
    public String inputJson;

    @Field(name = "output")
    public String output;

    // Downstream file references this node produced (AGENT lifts them from its child run; AGGREGATOR unions its
    // inputs'). Exposed as nodes.<id>.artifacts in the variable pool. Empty/absent for nodes that produce no file.
    @Field(name = "artifacts")
    public List<ArtifactRef> artifacts;

    @Field(name = "chosen_edge_ids")
    public List<String> chosenEdgeIds;

    @Field(name = "child_run_id")
    public String childRunId;

    @Field(name = "external_call_ref")
    public String externalCallRef;

    @Field(name = "span_id")
    public String spanId;

    @Field(name = "attempt")
    public Integer attempt;

    // Mirrors the owning run's preview flag so a TTL can co-expire preview node-runs with their preview run/version.
    @Field(name = "preview")
    public Boolean preview;

    @Field(name = "error")
    public String error;

    @Field(name = "started_at")
    public ZonedDateTime startedAt;

    @Field(name = "completed_at")
    public ZonedDateTime completedAt;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
