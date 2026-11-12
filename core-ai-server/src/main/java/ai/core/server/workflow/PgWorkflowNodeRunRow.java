package ai.core.server.workflow;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkflowNodeRunRow {
    @Column(name = "id")
    public String id;

    @Column(name = "run_id")
    public String runId;

    @Column(name = "workflow_id")
    public String workflowId;

    @Column(name = "node_id")
    public String nodeId;

    @Column(name = "node_type")
    public String nodeType;

    @Column(name = "scope_path_key")
    public String scopePathKey;

    @Column(name = "status")
    public String status;

    @Column(name = "input_json")
    public String inputJson;

    @Column(name = "output")
    public String output;

    @Column(name = "child_run_id")
    public String childRunId;

    @Column(name = "preview")
    public Boolean preview;

    @Column(name = "error")
    public String error;

    @Column(name = "started_at")
    public ZonedDateTime startedAt;

    @Column(name = "completed_at")
    public ZonedDateTime completedAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "artifacts")
    public String artifacts;

    @Column(name = "chosen_edge_ids")
    public String chosenEdgeIds;

    @Column(name = "data")
    public String data;
}
