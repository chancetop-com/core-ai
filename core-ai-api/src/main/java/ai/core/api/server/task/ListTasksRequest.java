package ai.core.api.server.task;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListTasksRequest {
    @QueryParam(name = "type")
    public String type;

    @QueryParam(name = "limit")
    public Integer limit;
}
