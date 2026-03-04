package ai.core.api.server.run;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListRunsRequest {
    @QueryParam(name = "status")
    public String status;

    @QueryParam(name = "limit")
    public Integer limit;
}
