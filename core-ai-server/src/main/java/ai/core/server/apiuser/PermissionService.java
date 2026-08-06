package ai.core.server.apiuser;

import ai.core.server.domain.User;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;

/**
 * Resource-level permission checks for API users (P1: agent resource type).
 * Future permission system entry point.
 *
 * @author stephen
 */
public class PermissionService {
    public static final String RESOURCE_TYPE_AGENT = "agent";

    @Inject
    MongoCollection<User> userCollection;

    public void check(String userId, String resourceType, String resourceId) {
        var user = userCollection.get(userId).orElse(null);
        if (user == null || !"api".equals(user.userType)) return;   // internal users keep existing behavior
        if (!hasPermission(user, resourceType, resourceId)) {
            throw new ForbiddenException("no permission to access " + resourceType + " " + resourceId);
        }
    }

    private boolean hasPermission(User user, String resourceType, String resourceId) {
        if (user.permissions == null || user.permissions.isEmpty()) return false;
        for (var permission : user.permissions) {
            if (resourceType.equals(permission.resourceType) && resourceId.equals(permission.resourceId)) {
                return true;
            }
        }
        return false;
    }
}
