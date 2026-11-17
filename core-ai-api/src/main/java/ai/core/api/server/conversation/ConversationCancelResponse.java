package ai.core.api.server.conversation;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ConversationCancelResponse {
    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "cancelled")
    public Boolean cancelled;
}
