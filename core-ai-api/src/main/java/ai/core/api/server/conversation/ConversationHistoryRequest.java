package ai.core.api.server.conversation;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ConversationHistoryRequest {
    /** Return messages with seq strictly below this value; null for the newest page. */
    @Property(name = "before_seq")
    public Long beforeSeq;

    @Property(name = "limit")
    public Integer limit;
}
