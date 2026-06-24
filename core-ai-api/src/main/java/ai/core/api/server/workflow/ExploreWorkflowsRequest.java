package ai.core.api.server.workflow;

import core.framework.api.web.service.QueryParam;

/**
 * @author Xander
 */
public class ExploreWorkflowsRequest {
    @QueryParam(name = "keyword")
    public String keyword;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
