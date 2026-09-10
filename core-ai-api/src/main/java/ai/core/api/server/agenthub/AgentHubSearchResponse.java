package ai.core.api.server.agenthub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Flat (ungrouped) agent search result.
 *
 * @author stephen
 */
public class AgentHubSearchResponse {
    @Property(name = "agents")
    public List<AgentHubSummary> agents;
}
