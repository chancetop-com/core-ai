package ai.core.mcp.server.apiserver;

import ai.core.mcp.server.apiserver.domain.ApiDefinition;

import java.util.List;

/**
 * @author stephen
 */
public interface ApiLoader {
    List<ApiDefinition> load();
}
