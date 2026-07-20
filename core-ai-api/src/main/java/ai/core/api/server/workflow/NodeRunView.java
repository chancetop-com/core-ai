package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author Xander
 */
public class NodeRunView {
    @Property(name = "node_id")
    public String nodeId;

    @Property(name = "node_type")
    public String nodeType;

    @Property(name = "status")
    public String status;

    @Property(name = "input")
    public String input;

    @Property(name = "output")
    public String output;

    @Property(name = "artifacts")
    public List<ArtifactView> artifacts;

    @Property(name = "error")
    public String error;

    @Property(name = "error_stack")
    public String errorStack;

    @Property(name = "child_run_id")
    public String childRunId;

    @Property(name = "child_run_type")
    public String childRunType;

    @Property(name = "child_workflow_id")
    public String childWorkflowId;

    @Property(name = "trace_id")
    public String traceId;

    @Property(name = "span_id")
    public String spanId;

    @Property(name = "trace_metadata")
    public NodeRunTraceMetadataView traceMetadata;

    @Property(name = "started_at")
    public ZonedDateTime startedAt;

    @Property(name = "completed_at")
    public ZonedDateTime completedAt;
}
