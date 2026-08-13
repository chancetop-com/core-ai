package ai.core.api.server.foryou;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ForYouTokenUsageRequest {
    @QueryParam(name = "range")
    public String range;

    @QueryParam(name = "from")
    public String from;

    @QueryParam(name = "to")
    public String to;
}
