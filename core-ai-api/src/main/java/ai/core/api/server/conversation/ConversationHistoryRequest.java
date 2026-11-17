package ai.core.api.server.conversation;

import core.framework.api.web.service.QueryParam;

/**
 * @author core-ai
 */
public class ConversationHistoryRequest {
    /** Return messages with seq strictly below this value; null for the newest page. */
    @QueryParam(name = "before_seq")
    public Long beforeSeq;

    @QueryParam(name = "limit")
    public Integer limit;
}
