package ai.core.api.server.dataset;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListDatasetsRequest {
    @QueryParam(name = "q")
    public String query;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
