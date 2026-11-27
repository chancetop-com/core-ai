package ai.core.api.server.workspace;

import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author stephen
 */
public interface WorkspaceWebService {
    @GET
    @Path("/api/workspaces")
    WorkspaceListView list(WorkspaceListRequest request);

    @POST
    @Path("/api/workspaces")
    WorkspaceView create(WorkspaceCreateRequest request);

    @GET
    @Path("/api/workspaces/:id")
    WorkspaceView get(@PathParam("id") String id);

    @PUT
    @Path("/api/workspaces/:id")
    WorkspaceView update(@PathParam("id") String id, WorkspaceUpdateRequest request);

    @DELETE
    @Path("/api/workspaces/:id")
    void delete(@PathParam("id") String id);

    @POST
    @Path("/api/workspaces/:id/wake")
    WorkspaceView wake(@PathParam("id") String id);

    @POST
    @Path("/api/workspaces/:id/sleep")
    WorkspaceView sleep(@PathParam("id") String id);

    @POST
    @Path("/api/workspaces/:id/reset")
    WorkspaceView reset(@PathParam("id") String id, WorkspaceResetRequest request);

    @GET
    @Path("/api/workspaces/:id/events")
    WorkspaceEventListView events(@PathParam("id") String id, WorkspaceEventListRequest request);

    @POST
    @Path("/api/workspaces/:id/undelete")
    WorkspaceView undelete(@PathParam("id") String id);
}
