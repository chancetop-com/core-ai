package ai.core.server.mcphub;

import ai.core.api.server.McpHubWebService;
import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.api.server.mcphub.HubSearchRequest;
import ai.core.api.server.mcphub.HubServersResponse;
import ai.core.api.server.mcphub.HubToolDetail;
import ai.core.api.server.mcphub.HubToolsResponse;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class McpHubWebServiceImpl implements McpHubWebService {
    @Inject
    McpHubService hubService;
    @Inject
    WebContext webContext;

    @Override
    @PermissionsRequired(PermissionCodes.MCP_CALL)
    public HubServersResponse servers() {
        return hubService.servers();
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_CALL)
    public HubToolsResponse search(HubSearchRequest request) {
        return hubService.search(request != null ? request.query : null,
                request != null ? request.server : null,
                request != null ? request.limit : null);
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_CALL)
    public HubToolDetail describe(String server, String tool) {
        return hubService.describe(server, tool);
    }

    @Override
    @PermissionsRequired(PermissionCodes.MCP_CALL)
    public HubCallResponse call(String server, String tool, HubCallRequest request) {
        var source = webContext.request().header("X-Core-AI-Client").orElse("unknown");
        return hubService.call(AuthContext.userId(webContext), source, server, tool, request);
    }
}
