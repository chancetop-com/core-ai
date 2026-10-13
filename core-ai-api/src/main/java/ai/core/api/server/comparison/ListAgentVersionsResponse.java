package ai.core.api.server.comparison;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListAgentVersionsResponse {
    @Property(name = "versions")
    public List<AgentVersionView> versions;
}
