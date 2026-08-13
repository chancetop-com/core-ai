package ai.core.api.server.ocg;

import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author stephen
 */
public interface OcgConfigWebService {
    @GET
    @Path("/api/admin/ocg-configs")
    ListOcgConfigsResponse list();

    @POST
    @Path("/api/admin/ocg-configs")
    OcgConfigResponse create(OcgConfigRequest request);

    @GET
    @Path("/api/admin/ocg-configs/:id")
    OcgConfigResponse get(@PathParam("id") String id);

    @PUT
    @Path("/api/admin/ocg-configs/:id")
    OcgConfigResponse update(@PathParam("id") String id, OcgConfigRequest request);

    @DELETE
    @Path("/api/admin/ocg-configs/:id")
    void delete(@PathParam("id") String id);

    @POST
    @Path("/api/admin/ocg-configs/:id/start")
    OcgConfigResponse start(@PathParam("id") String id);

    @POST
    @Path("/api/admin/ocg-configs/:id/stop")
    OcgConfigResponse stop(@PathParam("id") String id);

    @POST
    @Path("/api/admin/ocg-configs/:id/restart")
    OcgConfigResponse restart(@PathParam("id") String id);

    @POST
    @Path("/api/admin/ocg-configs/:id/command")
    OcgCommandResponse command(@PathParam("id") String id, OcgCommandRequest request);

    @GET
    @Path("/api/admin/ocg-configs/:id/logs")
    OcgLogsResponse logs(@PathParam("id") String id, OcgLogsRequest request);

    @GET
    @Path("/api/admin/ocg-configs/:id/status")
    OcgStatusResponse status(@PathParam("id") String id);
}
