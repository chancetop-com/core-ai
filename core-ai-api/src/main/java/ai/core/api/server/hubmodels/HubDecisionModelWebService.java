package ai.core.api.server.hubmodels;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * Decision models the caller may use for the decide tool and other structured-verdict consumers.
 * Kept separate from chat and media catalogs because those answer a different call shape.
 *
 * @author stephen
 */
public interface HubDecisionModelWebService {
    @GET
    @Path("/api/hub/decision-models")
    ListHubDecisionModelsResponse models();
}
