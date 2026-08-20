package ai.core.api.server.project;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListProjectMembersResponse {
    @Property(name = "agents")
    public List<ProjectMemberView> agents;

    @Property(name = "workflows")
    public List<ProjectMemberView> workflows;
}
