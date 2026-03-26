package ai.core.api.server.apidefinition;

import ai.core.api.apidefinition.ApiDefinition;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class SearchApiDefinitionResponse {
    @Property(name = "apis")
    public List<ApiDefinition> apis;
}
