package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class UpdateProjectRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "goal")
    public String goal;

    @Property(name = "playbook")
    public String playbook;

    @Property(name = "report_sources")
    public List<ProjectReportSourceRequest> reportSources;

    @Property(name = "status")
    public String status;
}
