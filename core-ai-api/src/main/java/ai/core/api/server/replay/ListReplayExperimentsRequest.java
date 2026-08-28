package ai.core.api.server.replay;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListReplayExperimentsRequest {
    @QueryParam(name = "agentId")
    public String agentId;

    // SPAN | BLANK, null = all
    @QueryParam(name = "origin")
    public String origin;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
