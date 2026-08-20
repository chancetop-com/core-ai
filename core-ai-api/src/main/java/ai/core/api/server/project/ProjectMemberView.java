package ai.core.api.server.project;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ProjectMemberView {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;
}
