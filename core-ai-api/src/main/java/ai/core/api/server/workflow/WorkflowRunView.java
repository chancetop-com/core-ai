package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author Xander
 */
public class WorkflowRunView {
    @Property(name = "id")
    public String id;

    @Property(name = "workflow_id")
    public String workflowId;

    @Property(name = "status")
    public String status;

    @Property(name = "input")
    public String input;

    @Property(name = "output")
    public String output;

    @Property(name = "error")
    public String error;

    @Property(name = "started_at")
    public ZonedDateTime startedAt;

    @Property(name = "completed_at")
    public ZonedDateTime completedAt;
}
