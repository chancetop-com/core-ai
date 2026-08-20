package ai.core.api.server.project;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListProjectsRequest {
    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;

    @QueryParam(name = "archived")
    public Boolean archived;
}
