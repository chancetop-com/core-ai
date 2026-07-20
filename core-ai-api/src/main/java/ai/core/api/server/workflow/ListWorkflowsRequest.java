package ai.core.api.server.workflow;

import core.framework.api.web.service.QueryParam;

/**
 * @author Xander
 */
public class ListWorkflowsRequest {
    // null -> caller's own workflows; "false" -> other users' published workflows (discover); "true" -> own
    @QueryParam(name = "my")
    public String myWorkflows;

    // "true" -> the caller's ARCHIVED workflows (read-only; run history stays reachable through them)
    @QueryParam(name = "archived")
    public Boolean archived;

    @QueryParam(name = "keyword")
    public String keyword;

    @QueryParam(name = "offset")
    public Integer offset;

    @QueryParam(name = "limit")
    public Integer limit;
}
