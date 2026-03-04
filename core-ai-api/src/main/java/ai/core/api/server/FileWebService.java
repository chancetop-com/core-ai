package ai.core.api.server;

import ai.core.api.server.file.FileView;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

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
}
