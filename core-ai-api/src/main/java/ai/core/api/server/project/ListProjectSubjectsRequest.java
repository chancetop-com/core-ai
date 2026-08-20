package ai.core.api.server.project;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListProjectSubjectsRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;

    @QueryParam(name = "query")
    public String query;
}
