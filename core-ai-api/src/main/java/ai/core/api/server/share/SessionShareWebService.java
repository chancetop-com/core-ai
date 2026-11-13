package ai.core.api.server.share;

import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * @author Xander
 */
public interface SessionShareWebService {
    @POST
    @Path("/api/sessions/:sessionId/shares")
    SessionShareView create(@PathParam("sessionId") String sessionId, CreateSessionShareRequest request);

    @GET
    @Path("/api/sessions/:sessionId/shares")
    SessionShareStatusResponse current(@PathParam("sessionId") String sessionId);

    @DELETE
    @Path("/api/sessions/:sessionId/shares/:shareId")
    void revoke(@PathParam("sessionId") String sessionId, @PathParam("shareId") String shareId);
}
