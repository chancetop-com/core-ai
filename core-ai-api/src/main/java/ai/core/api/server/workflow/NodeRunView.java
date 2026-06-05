package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

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

    @Property(name = "output")
    public String output;

    @Property(name = "error")
    public String error;

    @Property(name = "child_run_id")
    public String childRunId;

    @Property(name = "started_at")
    public ZonedDateTime startedAt;

    @Property(name = "completed_at")
    public ZonedDateTime completedAt;
}
