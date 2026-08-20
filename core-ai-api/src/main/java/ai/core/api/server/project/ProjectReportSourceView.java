package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ProjectReportSourceView {
    @Property(name = "type")
    public String type;   // agent | workflow

    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;
}
