package ai.core.api.server.evaluation.request;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListCasesRequest {
    @QueryParam(name = "split")
    public String split;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
