package ai.core.api.server.workflow;

import core.framework.api.json.Property;
import core.framework.api.validate.NotBlank;

/**
 * @author Xander
 */
public class CreateWorkflowRequest {
    @NotBlank
    @Property(name = "name")
    public String name;

    @Property(name = "mode")
    public String mode;   // WORKFLOW | CHATFLOW, defaults to WORKFLOW

    @NotBlank
    @Property(name = "graph")
    public String graph;   // canvas graph JSON
}
