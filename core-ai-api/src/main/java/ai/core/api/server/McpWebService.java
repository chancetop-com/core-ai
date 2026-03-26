package ai.core.api.server;

import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface McpWebService {
    @PUT
    @Path("/mcp/reload")
    void reload();
}
