package ai.core.api.server.memory;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListExperimentRunsRequest {
    @QueryParam(name = "agentId")
    public String agentId;

    @QueryParam(name = "skip")
    public Integer skip;

    @QueryParam(name = "limit")
    public Integer limit;
}
