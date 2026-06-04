package ai.core.api.server;

import ai.core.api.server.file.FileView;
import ai.core.api.server.file.FileShareView;
import ai.core.api.server.file.SharedFileView;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.POST;

/**
 * @author stephen
 */
public interface FileWebService {
    @GET
    @Path("/api/files/:id")
    FileView get(@PathParam("id") String id);

    @DELETE
    @Path("/api/files/:id")
    void delete(@PathParam("id") String id);

    @POST
    @Path("/api/files/:id/share")
    FileShareView share(@PathParam("id") String id);

    @GET
    @Path("/api/public/artifacts/:token")
    SharedFileView getShared(@PathParam("token") String token);
}
