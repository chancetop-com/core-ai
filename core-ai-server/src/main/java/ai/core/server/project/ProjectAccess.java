package ai.core.server.project;

import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.Project;
import ai.core.server.domain.User;
import ai.core.server.rbac.PermissionCodes;
import core.framework.mongo.MongoCollection;

/**
 * Access rules for the project feature. A project is a shared business container (its material
 * spans many people's agents), so access is permission-based rather than owner-exclusive:
 * the owner and admins always pass, everyone else needs the RBAC permission codes
 * (project.view to read, project.manage to control; manage implies view).
 *
 * @author stephen
 */
public final class ProjectAccess {
    public static boolean admin(MongoCollection<User> users, String userId) {
        if (userId == null || userId.isBlank()) return false;
        return users.get(userId).map(user -> "admin".equals(user.role)).orElse(Boolean.FALSE);
    }

    public static boolean canView(Project project, String userId, PermissionService permissions, MongoCollection<User> users) {
        if (userId == null || userId.isBlank()) return false;
        if (userId.equals(project.userId) || admin(users, userId)) return true;
        return permissions.has(userId, PermissionCodes.PROJECT_VIEW)
            || permissions.has(userId, PermissionCodes.PROJECT_MANAGE);
    }

    public static boolean canManage(Project project, String userId, PermissionService permissions, MongoCollection<User> users) {
        if (userId == null || userId.isBlank()) return false;
        if (userId.equals(project.userId) || admin(users, userId)) return true;
        return permissions.has(userId, PermissionCodes.PROJECT_MANAGE);
    }

    // whether the user can browse the full project list (permission holders see all projects,
    // because a project aggregates many people's work; others only see their own)
    public static boolean canViewAll(String userId, PermissionService permissions, MongoCollection<User> users) {
        if (userId == null || userId.isBlank()) return false;
        if (admin(users, userId)) return true;
        return permissions.has(userId, PermissionCodes.PROJECT_VIEW)
            || permissions.has(userId, PermissionCodes.PROJECT_MANAGE);
    }

    private ProjectAccess() {
    }
}
