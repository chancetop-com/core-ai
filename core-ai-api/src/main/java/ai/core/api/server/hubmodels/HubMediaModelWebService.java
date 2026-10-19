package ai.core.api.server.hubmodels;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * Media models the caller may use for generate_image / generate_video. User-scoped like
 * {@link HubModelWebService}, but kept separate because media models are registered against
 * different endpoint types and carry model-family parameter hints.
 *
 * @author stephen
 */
public interface HubMediaModelWebService {
    @GET
    @Path("/api/hub/media-models")
    ListHubMediaModelsResponse models();
}
