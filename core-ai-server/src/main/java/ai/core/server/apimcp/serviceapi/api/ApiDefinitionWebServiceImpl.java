package ai.core.server.apimcp.serviceapi.api;

import ai.core.api.server.ApiDefinitionWebService;
import ai.core.api.server.apidefinition.SearchApiDefinitionRequest;
import ai.core.api.server.apidefinition.SearchApiDefinitionResponse;
import ai.core.server.apimcp.serviceapi.service.ApiDefinitionService;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class ApiDefinitionWebServiceImpl implements ApiDefinitionWebService {
    @Inject
    ApiDefinitionService apiDefinitionService;

    @Override
    public SearchApiDefinitionResponse search(SearchApiDefinitionRequest request) {
        return apiDefinitionService.search(request);
    }
}
