package ai.core.api.server.hub;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * Read side of the hub call audit records ({@code hub_calls}) written by the MCP / API-tool Hub
 * execution paths; backs the observability "Hub Calls" page.
 *
 * @author stephen
 */
public interface HubCallWebService {
    @GET
    @Path("/api/hub/calls")
    ListHubCallsResponse list(ListHubCallsRequest request);
}
