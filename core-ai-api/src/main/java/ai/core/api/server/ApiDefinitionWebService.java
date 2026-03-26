package ai.core.api.server;

import ai.core.api.server.apidefinition.SearchApiDefinitionRequest;
import ai.core.api.server.apidefinition.SearchApiDefinitionResponse;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface ApiDefinitionWebService {
    @PUT
    @Path("/api-definition/search")
    SearchApiDefinitionResponse search(SearchApiDefinitionRequest request);
}
