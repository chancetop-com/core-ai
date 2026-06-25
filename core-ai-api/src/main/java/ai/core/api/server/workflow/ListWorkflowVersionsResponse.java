package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author Xander
 */
public class ListWorkflowVersionsResponse {
    @Property(name = "versions")
    public List<WorkflowVersionView> versions;
}
