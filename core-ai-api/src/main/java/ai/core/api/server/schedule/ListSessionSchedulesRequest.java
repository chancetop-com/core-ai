package ai.core.api.server.schedule;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListSessionSchedulesRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
