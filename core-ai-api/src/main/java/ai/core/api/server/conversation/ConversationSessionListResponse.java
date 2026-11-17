package ai.core.api.server.conversation;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class ConversationSessionListResponse {
    @Property(name = "sessions")
    public List<ConversationSessionSummary> sessions;

    @Property(name = "total")
    public Long total;
}
