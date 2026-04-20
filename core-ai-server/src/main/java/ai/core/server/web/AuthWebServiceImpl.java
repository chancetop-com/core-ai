package ai.core.server.web;

import ai.core.api.server.auth.AuthWebService;
import ai.core.api.server.auth.InviteRequest;
import ai.core.api.server.auth.ListUsersResponse;
import ai.core.api.server.auth.LoginRequest;
import ai.core.api.server.auth.LoginResponse;
import ai.core.api.server.auth.RegisterRequest;
import ai.core.api.server.auth.RegisterResponse;
import ai.core.api.server.auth.UpdateUserStatusRequest;
import ai.core.server.auth.AuthService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class AuthWebServiceImpl implements AuthWebService {
    @Inject
    WebContext webContext;
    @Inject
    AuthService authService;

    @Override
    public RegisterResponse register(RegisterRequest request) {
        ActionLogContext.put("email", request.email);
        return authService.register(request.email, request.password, request.name);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        ActionLogContext.put("email", request.email);
        return authService.login(request.email, request.password);
    }

    @Override
    public void invite(InviteRequest request) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("invite_email", request.email);
        authService.invite(userId, request.email);
    }

    @Override
    public ListUsersResponse listUsers() {
        var userId = AuthContext.userId(webContext);
        return authService.listUsers(userId);
    }

    @Override
    public void updateUserStatus(UpdateUserStatusRequest request) {
        var userId = AuthContext.userId(webContext);
        authService.updateUserStatus(userId, request.email, request.status);
    }
}
