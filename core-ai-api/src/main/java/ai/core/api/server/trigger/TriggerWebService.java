package ai.core.api.server.trigger;

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
public interface TriggerWebService {
    @POST
    @Path("/api/triggers")
    @ResponseStatus(HTTPStatus.CREATED)
    TriggerView create(CreateTriggerRequest request);

    @GET
    @Path("/api/triggers")
    ListTriggersResponse list(ListTriggersRequest request);

    @GET
    @Path("/api/triggers/:id")
    TriggerView get(@PathParam("id") String id);

    @PUT
    @Path("/api/triggers/:id")
    TriggerView update(@PathParam("id") String id, UpdateTriggerRequest request);

    @DELETE
    @Path("/api/triggers/:id")
    void delete(@PathParam("id") String id);

    @POST
    @Path("/api/triggers/:id/enable")
    TriggerView enable(@PathParam("id") String id);

    @POST
    @Path("/api/triggers/:id/disable")
    TriggerView disable(@PathParam("id") String id);

    @POST
    @Path("/api/triggers/:id/rotate-secret")
    TriggerView rotateSecret(@PathParam("id") String id);
}
