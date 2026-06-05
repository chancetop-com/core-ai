package ai.core.api.server.workflow;

import core.framework.api.json.Property;

/**
 * @author Xander
 */
public class UpdateWorkflowRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "graph")
    public String graph;   // updated canvas graph JSON
}
