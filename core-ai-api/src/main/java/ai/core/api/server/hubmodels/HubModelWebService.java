package ai.core.api.server.hubmodels;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * Chat models the caller may use, with their declared reasoning-effort levels. User-scoped by
 * design: the response is the projection of the same model-access policy that guards requests,
 * so "listed" always equals "usable" once per-user model access tightens.
 *
 * @author stephen
 */
public interface HubModelWebService {
    @GET
    @Path("/api/hub/models")
    ListHubModelsResponse models();
}
