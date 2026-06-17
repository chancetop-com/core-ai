package ai.core.api.server.auth;

import ai.core.api.server.user.GenerateApiKeyResponse;
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
    @Path("/api/auth/users/delete")
    void deleteUser(DeleteUserRequest request);

    @POST
    @Path("/api/auth/users/generate-api-key")
    GenerateApiKeyResponse generateApiKeyForUser(GenerateApiKeyForUserRequest request);

    @POST
    @Path("/api/auth/users/revoke-api-key")
    void revokeApiKey(RevokeApiKeyRequest request);

    @POST
    @Path("/api/auth/users/update-role")
    void updateUserRole(UpdateUserRoleRequest request);

    @POST
    @Path("/api/auth/users/reset-password")
    void resetUserPassword(ResetUserPasswordRequest request);
}
