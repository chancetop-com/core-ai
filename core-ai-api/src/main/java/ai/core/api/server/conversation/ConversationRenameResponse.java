package ai.core.api.server.conversation;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ConversationRenameResponse {
    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "title")
    public String title;
}
