package ai.core.server.web;

import ai.core.api.server.auth.AuthWebService;
import ai.core.api.server.auth.DeleteUserRequest;
import ai.core.api.server.auth.GenerateApiKeyForUserRequest;
import ai.core.api.server.auth.InviteRequest;
import ai.core.api.server.auth.ListUsersResponse;
import ai.core.api.server.auth.LoginRequest;
import ai.core.api.server.auth.LoginResponse;
import ai.core.api.server.auth.RegisterRequest;
import ai.core.api.server.auth.RegisterResponse;
import ai.core.api.server.auth.ResetUserPasswordRequest;
import ai.core.api.server.auth.RevokeApiKeyRequest;
import ai.core.api.server.auth.UpdateUserRoleRequest;
import ai.core.api.server.auth.UpdateUserStatusRequest;
import ai.core.api.server.auth.UserProfileView;
import ai.core.api.server.apiuser.request.UpdateApiUserConfigRequest;
import ai.core.api.server.user.GenerateApiKeyResponse;
import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.apiuser.ApiUserService;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.auth.AuthService;
import ai.core.server.domain.User;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.web.session.SessionIdentity;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.NotFoundException;

/**
 * @author stephen
 */
public class AuthWebServiceImpl implements AuthWebService {
    @Inject
    WebContext webContext;
    @Inject
    AuthService authService;
    @Inject
    ApiUserService apiUserService;
    @Inject
    ApiUserQuotaService apiUserQuotaService;
    @Inject
    PermissionService permissionService;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    SessionIdentity sessionIdentity;

    @Override
    public RegisterResponse register(RegisterRequest request) {
        ActionLogContext.put("email", request.email);
        return authService.register(request.email, request.password, request.name);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        ActionLogContext.put("email", request.email);
        var response = authService.login(request.email, request.password);
        sessionIdentity.setLoginSession(response.userId);
        return response;
    }

    @Override
    @PermissionsBypass
    public void logout() {
        sessionIdentity.invalidate();
    }

    @Override
    @PermissionsBypass
    public UserProfileView me() {
        var identity = sessionIdentity.getUserIdentityOrNull();
        if (identity != null) {
            var view = new UserProfileView();
            view.userId = identity.userId;
            view.name = identity.name;
            view.role = identity.role;
            view.permissions = identity.permissions;
            return view;
        }
        var userId = AuthContext.userId(webContext);
        var user = userCollection.get(userId)
                .orElseThrow(() -> new NotFoundException("user not found: " + userId));
        var view = new UserProfileView();
        view.userId = user.id;
        view.name = user.name;
        view.role = user.role;
        view.permissions = permissionService.permissionsOf(userId);
        return view;
    }

    @Override
    @PermissionsRequired(PermissionCodes.USER_MANAGE)
    public void invite(InviteRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("invite_email", request.email);
        authService.invite(userId, request.email);
    }

    @Override
    @PermissionsRequired(PermissionCodes.USER_MANAGE)
    public ListUsersResponse listUsers() {
        var userId = AuthContext.userId(webContext);
        return authService.listUsers(userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.USER_MANAGE)
    public void updateUserConfig(String userId, UpdateApiUserConfigRequest request) {
        var adminUserId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        apiUserService.updateConfigByAdmin(adminUserId, userId, request);
    }

    @Override
    @PermissionsRequired(PermissionCodes.USER_MANAGE)
    public void resetUserQuota(String userId) {
        AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        if (userCollection.get(userId).isEmpty()) {
            throw new NotFoundException("user not found, userId=" + userId);
        }
        apiUserQuotaService.resetQuota(userId);
    }

    @Override
    @PermissionsRequired(PermissionCodes.USER_MANAGE)
    public void updateUserStatus(UpdateUserStatusRequest request) {
        var userId = AuthContext.userId(webContext);
        authService.updateUserStatus(userId, request.email, request.status);
    }

    @Override
    @PermissionsRequired(PermissionCodes.USER_MANAGE)
    public void deleteUser(DeleteUserRequest request) {
        var userId = AuthContext.userId(webContext);
        authService.deleteUser(userId, request.email);
    }

    @Override
    @PermissionsRequired(PermissionCodes.USER_MANAGE)
    public GenerateApiKeyResponse generateApiKeyForUser(GenerateApiKeyForUserRequest request) {
        var userId = AuthContext.userId(webContext);
        return authService.generateApiKeyForUser(userId, request.email);
    }

    @Override
    @PermissionsRequired(PermissionCodes.USER_MANAGE)
    public void revokeApiKey(RevokeApiKeyRequest request) {
        var userId = AuthContext.userId(webContext);
        authService.revokeApiKey(userId, request.email);
    }

    @Override
    @PermissionsRequired(PermissionCodes.USER_MANAGE)
    public void updateUserRole(UpdateUserRoleRequest request) {
        var userId = AuthContext.userId(webContext);
        authService.updateUserRole(userId, request.email, request.role);
    }

    @Override
    @PermissionsRequired(PermissionCodes.USER_MANAGE)
    public void resetUserPassword(ResetUserPasswordRequest request) {
        var userId = AuthContext.userId(webContext);
        authService.resetUserPassword(userId, request.email, request.password);
    }
}
