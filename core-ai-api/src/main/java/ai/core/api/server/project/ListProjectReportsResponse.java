package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListProjectReportsResponse {
    @Property(name = "reports")
    public List<ProjectReportView> reports;
}
