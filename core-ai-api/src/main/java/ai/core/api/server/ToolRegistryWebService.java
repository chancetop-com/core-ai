package ai.core.api.server;

import ai.core.api.server.tool.CreateMcpServerRequest;
import ai.core.api.server.tool.ListApiAppServicesResponse;
import ai.core.api.server.tool.ListApiAppsResponse;
import ai.core.api.server.tool.ListToolCategoriesResponse;
import ai.core.api.server.tool.ListToolsRequest;
import ai.core.api.server.tool.ListToolsResponse;
import ai.core.api.server.tool.ToolRegistryView;
import ai.core.api.server.tool.UpdateMcpServerRequest;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
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

    @POST
    @Path("/api/tools/mcp-servers")
    ToolRegistryView createMcpServer(CreateMcpServerRequest request);

    @PUT
    @Path("/api/tools/mcp-servers/:id")
    ToolRegistryView updateMcpServer(@PathParam("id") String id, UpdateMcpServerRequest request);

    @DELETE
    @Path("/api/tools/mcp-servers/:id")
    void deleteMcpServer(@PathParam("id") String id);

    @PUT
    @Path("/api/tools/mcp-servers/:id/enable")
    ToolRegistryView enableMcpServer(@PathParam("id") String id);

    @PUT
    @Path("/api/tools/mcp-servers/:id/disable")
    ToolRegistryView disableMcpServer(@PathParam("id") String id);

    @GET
    @Path("/api/tools/service-api/apps")
    ListApiAppsResponse listServiceApiApps();

    @GET
    @Path("/api/tools/service-api/apps/:appName/services")
    ListApiAppServicesResponse listApiAppServices(@PathParam("appName") String appName);
}
