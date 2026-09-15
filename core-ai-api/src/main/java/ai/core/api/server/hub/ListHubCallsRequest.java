package ai.core.api.server.hub;

import core.framework.api.web.service.QueryParam;

/**
 * Always scoped to a time window: {@code range} (default 7d) or explicit {@code startFrom}/{@code startTo}.
 *
 * @author stephen
 */
public class ListHubCallsRequest {
    @QueryParam(name = "kind")
    public String kind;

    @QueryParam(name = "source")
    public String source;

    @QueryParam(name = "userId")
    public String userId;

    @QueryParam(name = "state")
    public String state;

    @QueryParam(name = "range")
    public String range;

    @QueryParam(name = "startFrom")
    public String startFrom;

    @QueryParam(name = "startTo")
    public String startTo;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
