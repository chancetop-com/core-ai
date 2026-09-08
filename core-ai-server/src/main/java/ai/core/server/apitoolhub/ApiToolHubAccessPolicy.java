package ai.core.server.apitoolhub;

import ai.core.server.apiuser.PermissionService;
import core.framework.inject.Inject;

/**
 * App-level access decisions for API-Tool Hub operations, applied after the route-level
 * {@code apitool.call} permission has passed:
 * <ul>
 *   <li>disabled operations never reach the catalog, so they surface as 404 on call;</li>
 *   <li>API users ({@code userType=api}) are scoped by their {@code api-app} resource
 *       whitelist; internal users are already covered by the {@code apitool.call} check.</li>
 * </ul>
 * P1 adds per-app access modes here without touching the web layer.
 *
 * @author stephen
 */
public class ApiToolHubAccessPolicy {
    @Inject
    PermissionService permissionService;

    public void checkCanCall(String userId, String app) {
        if (userId != null && permissionService.isApiUser(userId)) {
            permissionService.check(userId, PermissionService.RESOURCE_TYPE_API_APP, app);
        }
    }

    /** Whether an API user may see/call a given app (whitelist); unrestricted users always pass. */
    public boolean canAccessApp(String userId, String app) {
        if (userId == null || !permissionService.isApiUser(userId)) return true;
        return permissionService.checkResource(userId, PermissionService.RESOURCE_TYPE_API_APP, app);
    }

    public boolean isApiUser(String userId) {
        return userId != null && permissionService.isApiUser(userId);
    }
}
