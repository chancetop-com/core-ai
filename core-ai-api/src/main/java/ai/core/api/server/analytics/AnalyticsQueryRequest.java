package ai.core.api.server.analytics;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class AnalyticsQueryRequest {
    @QueryParam(name = "mode")
    public String mode;

    @QueryParam(name = "range")
    public String range;

    @QueryParam(name = "from")
    public String from;

    @QueryParam(name = "to")
    public String to;

    @QueryParam(name = "sort")
    public String sort;
}
