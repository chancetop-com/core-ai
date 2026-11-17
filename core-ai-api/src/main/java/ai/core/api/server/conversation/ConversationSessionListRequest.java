package ai.core.api.server.conversation;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class ConversationSessionListRequest {
    @Property(name = "offset")
    public Integer offset;

    @Property(name = "limit")
    public Integer limit;
}
