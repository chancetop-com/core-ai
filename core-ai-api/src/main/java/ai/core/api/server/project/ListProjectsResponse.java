package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListProjectsResponse {
    @Property(name = "projects")
    public List<ProjectSummaryView> projects;

    @Property(name = "total")
    public Long total;
}
