package ai.core.server.apiuser;

import ai.core.server.domain.User;
import ai.core.server.rbac.RoleRegistry;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;

import java.util.List;

/**
 * Unified permission checks for all users.
 * <p>
 * Resource-level: {@link #check(String, String, String)} — whitelist of (resourceType, resourceId),
 * unconfigured users are unrestricted, configured users must match exactly.
 * Action-level: {@link #check(String, String)} — RBAC role based (admin wildcard, manage implies view).
 * API users ({@code userType=api}) are exempt from action checks; their perimeter is key scope +
 * resource whitelist + quota.
 *
 * @author stephen
 */
public class PermissionService {
    public static final String RESOURCE_TYPE_AGENT = "agent";
    private static final String USER_TYPE_API = "api";

    @Inject
    MongoCollection<User> userCollection;
    @Inject
    RoleRegistry roleRegistry;

    public void check(String userId, String resourceType, String resourceId) {
        var user = userCollection.get(userId).orElse(null);
        if (user == null || user.permissions == null || user.permissions.isEmpty()) return;
        if (!hasResourcePermission(user, resourceType, resourceId)) {
            throw new ForbiddenException("no permission to access " + resourceType + " " + resourceId);
        }
    }

    public void check(String userId, String permission) {
        if (!has(userId, permission)) {
            throw new ForbiddenException("permission required: " + permission);
        }
    }

    public boolean has(String userId, String permission) {
        var user = userCollection.get(userId).orElse(null);
        if (user == null) return false;
        if (USER_TYPE_API.equals(user.userType)) return true;
        var permissions = roleRegistry.permissionsOf(user.role);
        if (permissions.contains(RoleRegistry.ALL_PERMISSIONS)) return true;
        if (permissions.contains(permission)) return true;
        if (permission.endsWith(".view")) {
            return permissions.contains(permission.substring(0, permission.length() - ".view".length()) + "manage");
        }
        return false;
    }

    public List<String> permissionsOf(String userId) {
        var user = userCollection.get(userId).orElse(null);
        if (user == null) return List.of();
        return roleRegistry.permissionsOf(user.role);
    }

    private boolean hasResourcePermission(User user, String resourceType, String resourceId) {
        if (user.permissions == null || user.permissions.isEmpty()) return false;
        for (var permission : user.permissions) {
            if (resourceType.equals(permission.resourceType) && resourceId.equals(permission.resourceId)) {
                return true;
            }
        }
        return false;
    }
}
