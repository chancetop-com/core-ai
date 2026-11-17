package ai.core.api.server.conversation;

import ai.core.api.server.agenthub.AgentHubSummary;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class ConversationAgentListResponse {
    @Property(name = "agents")
    public List<AgentHubSummary> agents;
}
