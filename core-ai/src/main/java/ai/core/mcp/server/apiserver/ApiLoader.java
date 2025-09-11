package ai.core.mcp.server.apiserver;

import ai.core.api.apidefinition.ApiDefinition;

import java.util.List;

/**
 * @author stephen
 */
public interface ApiLoader {
    List<ApiDefinition> load();
    default List<String> defaultNamespaces() {
        return null;
    }
}
