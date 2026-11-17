package ai.core.api.server.conversation;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ConversationAgentListRequest {
    @Property(name = "query")
    public String query;

    @Property(name = "limit")
    public Integer limit;
}
