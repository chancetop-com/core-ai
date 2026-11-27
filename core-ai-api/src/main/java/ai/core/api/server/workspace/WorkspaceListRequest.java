package ai.core.api.server.workspace;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class WorkspaceListRequest {
    /** includes soft-deleted rows, so the settings page can offer undelete */
    @QueryParam(name = "include_deleted")
    public Boolean includeDeleted;
}
