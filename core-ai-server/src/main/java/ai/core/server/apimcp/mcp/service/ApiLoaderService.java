package ai.core.server.apimcp.mcp.service;

import ai.core.api.apidefinition.ApiDefinition;
import ai.core.mcp.server.apiserver.ApiLoader;
import ai.core.server.apimcp.serviceapi.service.ApiDefinitionService;
import core.framework.inject.Inject;

import java.util.List;

/**
 * @author stephen
 */
public class ApiLoaderService implements ApiLoader {
    @Inject
    ApiDefinitionService apiDefinitionService;

    @Override
    public List<ApiDefinition> load() {
        return apiDefinitionService.loadAll();
    }

    @Override
    public List<String> defaultNamespaces() {
        return List.of("operation-assistant-api");
    }
}
