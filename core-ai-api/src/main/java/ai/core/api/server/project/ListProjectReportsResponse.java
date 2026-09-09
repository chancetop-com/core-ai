package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListProjectReportsResponse {
    @Property(name = "reports")
    public List<ProjectReportView> reports;

    // matching reports before paging (inbox: within the bounded scan of recent member material)
    @Property(name = "total")
    public Long total;
}
