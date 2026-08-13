package ai.core.api.server.session;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListChatSessionsRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;

    @QueryParam(name = "sources")
    public String sources;

    @QueryParam(name = "agent_ids")
    public String agentIds;
}
