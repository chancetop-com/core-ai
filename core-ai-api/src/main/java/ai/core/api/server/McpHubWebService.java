package ai.core.api.server;

import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.api.server.mcphub.HubSearchRequest;
import ai.core.api.server.mcphub.HubServersResponse;
import ai.core.api.server.mcphub.HubToolDetail;
import ai.core.api.server.mcphub.HubToolsResponse;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * MCP Hub read/search/call surface for non-agent consumers (CLI, scripts, external agents).
 * Never exposes server config/headers/env — credentials stay on the server.
 *
 * @author stephen
 */
public interface McpHubWebService {
    @GET
    @Path("/api/mcp-hub/servers")
    HubServersResponse servers();

    @GET
    @Path("/api/mcp-hub/tools")
    HubToolsResponse search(HubSearchRequest request);

    @GET
    @Path("/api/mcp-hub/tools/:server/:tool")
    HubToolDetail describe(@PathParam("server") String server, @PathParam("tool") String tool);

    @POST
    @Path("/api/mcp-hub/tools/:server/:tool/call")
    HubCallResponse call(@PathParam("server") String server, @PathParam("tool") String tool, HubCallRequest request);
}
