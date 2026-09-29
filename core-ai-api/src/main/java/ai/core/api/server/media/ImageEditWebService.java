package ai.core.api.server.media;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;

/**
 * Region edit: repaint only the area a user painted, without going through an agent.
 *
 * @author stephen
 */
public interface ImageEditWebService {
    @GET
    @Path("/api/media/image-edit-models")
    ImageEditModelListResponse models();

    @POST
    @Path("/api/media/image-edits")
    ImageEditResponse edit(ImageEditRequest request);
}
