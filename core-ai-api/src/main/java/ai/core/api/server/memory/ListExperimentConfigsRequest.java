package ai.core.api.server.memory;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListExperimentConfigsRequest {
    @QueryParam(name = "skip")
    public Integer skip;

    @QueryParam(name = "limit")
    public Integer limit;
}
