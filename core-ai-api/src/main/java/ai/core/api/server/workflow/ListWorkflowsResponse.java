package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author Xander
 */
public class ListWorkflowsResponse {
    @Property(name = "workflows")
    public List<WorkflowView> workflows;
}
