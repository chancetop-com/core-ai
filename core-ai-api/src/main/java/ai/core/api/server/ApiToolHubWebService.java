package ai.core.api.server;

import ai.core.api.server.apitoolhub.ApiToolHubAppsResponse;
import ai.core.api.server.apitoolhub.ApiToolHubLookupRequest;
import ai.core.api.server.apitoolhub.ApiToolHubLookupResponse;
import ai.core.api.server.apitoolhub.ApiToolHubOperationDetail;
import ai.core.api.server.apitoolhub.ApiToolHubSearchRequest;
import ai.core.api.server.apitoolhub.ApiToolHubSearchResponse;
import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * API-Tool Hub read/search/call surface over the Service API catalog for non-agent
 * consumers (CLI, scripts, external agents). Operations are addressed as
 * {@code {app}/{service}/{operation}}; the same catalog backs the agent-side
 * {@code api-app:}/{@code api-service:}/{@code api-operation:} tool refs.
 * Schemas are dynamic JSON and travel as text.
 *
 * @author stephen
 */
public interface ApiToolHubWebService {
    @GET
    @Path("/api/hub/api-tools/apps")
    ApiToolHubAppsResponse apps();

    @GET
    @Path("/api/hub/api-tools")
    ApiToolHubSearchResponse search(ApiToolHubSearchRequest request);

    @GET
    @Path("/api/hub/api-tools/lookup")
    ApiToolHubLookupResponse lookup(ApiToolHubLookupRequest request);

    @GET
    @Path("/api/hub/api-tools/:app/:service/:operation")
    ApiToolHubOperationDetail describe(@PathParam("app") String app,
                                       @PathParam("service") String service,
                                       @PathParam("operation") String operation);

    @POST
    @Path("/api/hub/api-tools/:app/:service/:operation/call")
    HubCallResponse call(@PathParam("app") String app,
                         @PathParam("service") String service,
                         @PathParam("operation") String operation,
                         HubCallRequest request);
}
