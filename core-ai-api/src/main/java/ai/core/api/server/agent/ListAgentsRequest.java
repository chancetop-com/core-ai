package ai.core.api.server.agent;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListAgentsRequest {
    @QueryParam(name = "my")
    public String myAgents;

    @QueryParam(name = "query")
    public String query;

    @QueryParam(name = "page")
    public Integer page;

    @QueryParam(name = "limit")
    public Integer limit;

    @QueryParam(name = "sort")
    public String sort;

    @QueryParam(name = "include_system_default")
    public Boolean includeSystemDefault;
}
