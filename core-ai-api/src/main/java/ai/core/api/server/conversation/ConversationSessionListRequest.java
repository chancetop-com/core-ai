package ai.core.api.server.conversation;

import core.framework.api.web.service.QueryParam;

/**
 * @author core-ai
 */
public class ConversationSessionListRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
