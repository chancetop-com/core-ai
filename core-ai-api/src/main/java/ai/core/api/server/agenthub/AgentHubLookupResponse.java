package ai.core.api.server.agenthub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class AgentHubLookupResponse {
    @Property(name = "candidates")
    public List<AgentHubSummary> candidates;
}
