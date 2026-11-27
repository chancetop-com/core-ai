package ai.core.api.server.workspace;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class WorkspaceEventListRequest {
    @QueryParam(name = "limit")
    public Integer limit;
}
