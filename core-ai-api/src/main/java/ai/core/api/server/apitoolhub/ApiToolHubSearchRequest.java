package ai.core.api.server.apitoolhub;

import core.framework.api.json.Property;

/**
 * Search query over the API-Tool Hub catalog. When {@code query} is blank the result
 * is a flat listing (bounded by {@code limit}, default 20, max 200).
 *
 * @author stephen
 */
public class ApiToolHubSearchRequest {
    @Property(name = "query")
    public String query;

    @Property(name = "app")
    public String app;

    @Property(name = "service")
    public String service;

    @Property(name = "limit")
    public Integer limit;
}
