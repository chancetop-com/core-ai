package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author Xander
 */
public class ListNodeRunsResponse {
    @Property(name = "node_runs")
    public List<NodeRunView> nodeRuns;
}
