package ai.core.server.mcphub;

import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.ToolRegistryEntry;
import core.framework.inject.Inject;
import core.framework.web.exception.NotFoundException;

/**
 * Entry-level access decisions for MCP Hub operations, applied after the route-level
 * {@code mcp.call} permission has passed:
 * <ul>
 *   <li>disabled entries are treated as absent (404);</li>
 *   <li>API users ({@code userType=api}) are scoped by their {@code mcp-server} resource
 *       whitelist; internal users are already covered by the {@code mcp.call} action check.</li>
 * </ul>
 * P1 adds per-server ACL modes here without touching the web layer.
 *
 * @author stephen
 */
public class McpHubAccessPolicy {
    @Inject
    PermissionService permissionService;

    public void checkCanCall(String userId, ToolRegistryEntry entry) {
        if (!Boolean.TRUE.equals(entry.enabled)) {
            throw new NotFoundException("mcp server not found or disabled: " + entry.name);
        }
        if (userId != null && permissionService.isApiUser(userId)) {
            permissionService.check(userId, PermissionService.RESOURCE_TYPE_MCP_SERVER, entry.id);
        }
    }

    public boolean isApiUser(String userId) {
        return userId != null && permissionService.isApiUser(userId);
    }
}
