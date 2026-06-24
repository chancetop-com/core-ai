package ai.core.api.server.workflow;

import core.framework.api.web.service.QueryParam;

/**
 * @author Xander
 */
public class ListWorkflowsRequest {
    // null -> caller's own workflows; "false" -> other users' published workflows (discover); "true" -> own
    @QueryParam(name = "my")
    public String myWorkflows;
}
