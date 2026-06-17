package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * Portable export envelope for a workflow draft. The graph stays an opaque JSON string (same representation as
 * WorkflowDefinition.draftGraph). Reused server-side to parse an uploaded file back into the same shape.
 *
 * @author Xander
 */
public class ExportWorkflowResponse {
    @Property(name = "format")
    public String format;

    @Property(name = "exported_at")
    public ZonedDateTime exportedAt;

    @Property(name = "name")
    public String name;

    @Property(name = "mode")
    public String mode;   // WORKFLOW | CHATFLOW

    @Property(name = "description")
    public String description;

    @Property(name = "graph")
    public String graph;   // canvas graph JSON, opaque string
}
