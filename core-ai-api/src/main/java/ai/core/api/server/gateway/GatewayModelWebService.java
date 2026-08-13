package ai.core.api.server.gateway;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * @author stephen
 */
public interface GatewayModelWebService {
    @GET
    @Path("/api/gateway/models")
    ListGatewayModelsResponse list();

    @GET
    @Path("/api/gateway/models/available")
    ListGatewayAvailableModelsResponse listAvailable();

    @POST
    @Path("/api/gateway/models")
    @ResponseStatus(HTTPStatus.CREATED)
    GatewayModelView create(GatewayModelRequest request);

    @PUT
    @Path("/api/gateway/models/:id")
    GatewayModelView update(@PathParam("id") String id, GatewayModelRequest request);

    @DELETE
    @Path("/api/gateway/models/:id")
    void delete(@PathParam("id") String id);

    @POST
    @Path("/api/gateway/models/:id/set-default")
    GatewayModelView markDefault(@PathParam("id") String id);

    @POST
    @Path("/api/gateway/providers/:id/models/discover")
    ListGatewayDiscoveredModelsResponse discover(@PathParam("id") String id);

    @POST
    @Path("/api/gateway/providers/:id/models/import")
    ListGatewayModelsResponse importModels(@PathParam("id") String id, ImportGatewayModelsRequest request);
}
