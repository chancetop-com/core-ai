package ai.core.server.apimcp.mcp.service;

import ai.core.mcp.server.McpServerService;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class McpService {
    @Inject
    McpServerService mcpServerService;

    public void reload() {
        mcpServerService.reload();
    }
}
