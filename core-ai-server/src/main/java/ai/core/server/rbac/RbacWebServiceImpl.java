package ai.core.server.rbac;

import ai.core.api.server.rbac.RoleConfigView;
import ai.core.api.server.rbac.RbacWebService;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.User;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;

/**
 * @author stephen
 */
public class RbacWebServiceImpl implements RbacWebService {
    @Inject
    WebContext webContext;
    @Inject
    RoleRegistry roleRegistry;
    @Inject
    PermissionService permissionService;
    @Inject
    MongoCollection<User> userCollection;

    @Override
    @PermissionsRequired(PermissionCodes.RBAC_MANAGE)
    public RoleConfigView listRoles() {
        var userId = AuthContext.userId(webContext);
        permissionService.check(userId, PermissionCodes.RBAC_MANAGE);
        var view = new RoleConfigView();
        view.roles = roleRegistry.effectiveRoles();
        view.catalog = PermissionCodes.ALL;
        return view;
    }

    @Override
    public void updateRoles(RoleConfigView request) {
        var userId = AuthContext.userId(webContext);
        requireAdmin(userId);   // hard admin check so roles cannot self-escalate via rbac.manage
        roleRegistry.updateRoles(request.roles);
    }

    private void requireAdmin(String userId) {
        var user = userCollection.get(userId)
                .orElseThrow(() -> new ForbiddenException("admin required"));
        if (!RoleRegistry.ROLE_ADMIN.equals(user.role)) {
            throw new ForbiddenException("admin required");
        }
    }
}
