package ai.core.api.server.conversation;

import core.framework.api.web.service.QueryParam;

/**
 * @author core-ai
 */
public class ConversationAgentListRequest {
    @QueryParam(name = "query")
    public String query;

    @QueryParam(name = "limit")
    public Integer limit;
}
