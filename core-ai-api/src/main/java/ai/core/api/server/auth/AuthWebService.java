package ai.core.api.server.auth;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.ResponseStatus;

/**
 * @author stephen
 */
public interface AuthWebService {
    @POST
    @Path("/api/auth/register")
    @ResponseStatus(HTTPStatus.CREATED)
    RegisterResponse register(RegisterRequest request);

    @POST
    @Path("/api/auth/login")
    LoginResponse login(LoginRequest request);

    @POST
    @Path("/api/auth/invite")
    void invite(InviteRequest request);

    @GET
    @Path("/api/auth/users")
    ListUsersResponse listUsers();

    @POST
    @Path("/api/auth/users/update-status")
    void updateUserStatus(UpdateUserStatusRequest request);

    @POST
    @Path("/api/auth/users/reset-admin-password")
    void resetAdminPassword();

    @POST
    @Path("/api/auth/users/delete")
    void deleteUser(DeleteUserRequest request);
}
