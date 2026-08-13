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
public interface GatewayProviderWebService {
    @GET
    @Path("/api/gateway/providers")
    ListGatewayProvidersResponse list();

    @POST
    @Path("/api/gateway/providers")
    @ResponseStatus(HTTPStatus.CREATED)
    GatewayProviderView create(GatewayProviderRequest request);

    @PUT
    @Path("/api/gateway/providers/:id")
    GatewayProviderView update(@PathParam("id") String id, GatewayProviderRequest request);

    @DELETE
    @Path("/api/gateway/providers/:id")
    void delete(@PathParam("id") String id, DeleteGatewayProviderRequest request);

    @POST
    @Path("/api/gateway/providers/:id/test")
    TestGatewayProviderResponse test(@PathParam("id") String id);
}
