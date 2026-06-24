package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListAgentsResponse {
    @Property(name = "agents")
    public List<AgentDefinitionView> agents;

    @Property(name = "total")
    public Long total;

    @Property(name = "page")
    public Integer page;

    @Property(name = "limit")
    public Integer limit;
}
