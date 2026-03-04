package ai.core.api.server;

import ai.core.api.server.tool.ListToolCategoriesResponse;
import ai.core.api.server.tool.ListToolsRequest;
import ai.core.api.server.tool.ListToolsResponse;
import ai.core.api.server.tool.ToolRegistryView;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author stephen
 */
public interface ToolRegistryWebService {
    @GET
    @Path("/api/tools")
    ListToolsResponse list(ListToolsRequest request);

    @GET
    @Path("/api/tools/categories")
    ListToolCategoriesResponse categories();

    @GET
    @Path("/api/tools/:id")
    ToolRegistryView get(@PathParam("id") String id);
}
