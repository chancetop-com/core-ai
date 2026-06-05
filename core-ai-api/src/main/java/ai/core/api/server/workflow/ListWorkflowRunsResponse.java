package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author Xander
 */
public class ListWorkflowRunsResponse {
    @Property(name = "runs")
    public List<WorkflowRunView> runs;
}
