package ai.core.api.server.tool;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class ListToolsRequest {
    @QueryParam(name = "category")
    public String category;
}
