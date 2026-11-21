package ai.core.api.server.evaluation.request;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListRunsRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
