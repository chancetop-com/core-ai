package ai.core.server.apimcp.mcp.api;

import ai.core.api.server.McpWebService;
import ai.core.server.apimcp.mcp.service.McpService;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class McpWebServiceImpl implements McpWebService {
    @Inject
    McpService mcpService;

    @Override
    public void reload() {
        mcpService.reload();
    }
}
