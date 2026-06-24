package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author Xander
 */
public class ExploreWorkflowsResponse {
    @Property(name = "workflows")
    public List<WorkflowView> workflows;

    @Property(name = "total")
    public Long total;
}
