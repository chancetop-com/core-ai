package ai.core.server.apimcp.mcp.api;

import ai.core.api.server.McpWebService;
import ai.core.server.apimcp.mcp.service.McpService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.APITOOL_MANAGE)
public class McpWebServiceImpl implements McpWebService {
    @Inject
    McpService mcpService;

    @Override
    public void reload() {
        mcpService.reload();
    }
}
