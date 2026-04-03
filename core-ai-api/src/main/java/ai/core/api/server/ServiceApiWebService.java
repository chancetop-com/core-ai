package ai.core.api.server;

import ai.core.api.server.serviceapi.CreateApiRequest;
import ai.core.api.server.serviceapi.ListServiceApiResponse;
import ai.core.api.server.serviceapi.ServiceApiView;
import ai.core.api.server.serviceapi.UpdateAllFromSysApiRequest;
import ai.core.api.server.serviceapi.UpdateApiRequest;
import ai.core.api.server.serviceapi.UpdateFromSysApiRequest;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author stephen
 */
public interface ServiceApiWebService {
    @POST
    @Path("/api/service-api")
    void create(CreateApiRequest request);

    @DELETE
    @Path("/api/service-api/:id")
    void delete(@PathParam("id") String id);

    @PUT
    @Path("/api/service-api/:id")
    void update(@PathParam("id") String id, UpdateApiRequest request);

    @PUT
    @Path("/api/service-api/:id/update-from-sys-api")
    void updateFromSysApi(@PathParam("id") String id, UpdateFromSysApiRequest request);

    @GET
    @Path("/service-api/:id")
    ServiceApiView get(@PathParam("id") String id);

    @GET
    @Path("/service-api")
    ListServiceApiResponse list();

    @PUT
    @Path("/service-api/update-all-from-sys-api")
    void updateAllFromSysApi(UpdateAllFromSysApiRequest request);
}
