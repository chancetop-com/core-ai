package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListProjectExecutionsResponse {
    @Property(name = "executions")
    public List<ProjectExecutionView> executions;

    @Property(name = "total")
    public Long total;
}
