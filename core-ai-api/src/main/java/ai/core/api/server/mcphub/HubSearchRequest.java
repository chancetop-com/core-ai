package ai.core.api.server.mcphub;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class HubSearchRequest {
    @QueryParam(name = "query")
    public String query;

    @QueryParam(name = "server")
    public String server;

    @QueryParam(name = "limit")
    public Integer limit;
}
