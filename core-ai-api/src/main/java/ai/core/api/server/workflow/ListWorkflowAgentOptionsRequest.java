package ai.core.api.server.workflow;

import core.framework.api.web.service.QueryParam;

/**
 * @author Xander
 */
public class ListWorkflowAgentOptionsRequest {
    @QueryParam(name = "scope")
    public String scope;

    @QueryParam(name = "type")
    public String type;

    @QueryParam(name = "query")
    public String query;

    @QueryParam(name = "page")
    public Integer page;

    @QueryParam(name = "limit")
    public Integer limit;

    @QueryParam(name = "selected_id")
    public String selectedId;
}
