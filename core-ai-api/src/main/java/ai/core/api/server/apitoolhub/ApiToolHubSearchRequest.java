package ai.core.api.server.apitoolhub;

import core.framework.api.web.service.QueryParam;

/**
 * Search query over the API-Tool Hub catalog. When {@code query} is blank the result
 * is a flat listing (bounded by {@code limit}, default 20, max 200).
 *
 * @author stephen
 */
public class ApiToolHubSearchRequest {
    @QueryParam(name = "query")
    public String query;

    @QueryParam(name = "app")
    public String app;

    @QueryParam(name = "service")
    public String service;

    @QueryParam(name = "limit")
    public Integer limit;
}
