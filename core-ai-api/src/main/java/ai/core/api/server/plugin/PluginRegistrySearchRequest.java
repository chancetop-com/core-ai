package ai.core.api.server.plugin;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class PluginRegistrySearchRequest {
    @QueryParam(name = "query")
    public String query;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
