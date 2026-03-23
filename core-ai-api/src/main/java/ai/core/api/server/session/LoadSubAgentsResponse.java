package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class LoadSubAgentsResponse {
    @Property(name = "loaded_sub_agents")
    public List<String> loadedSubAgents;
}
