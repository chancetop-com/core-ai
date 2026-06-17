package ai.core.api.server;

import ai.core.api.server.user.ApiKeyView;
import ai.core.api.server.user.ChangePasswordRequest;
import ai.core.api.server.user.GenerateApiKeyResponse;
import ai.core.api.server.user.UserView;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.ResponseStatus;

/**
 * @author stephen
 */
public interface UserWebService {
    @GET
    @Path("/api/user/me")
    UserView me();

    @GET
    @Path("/api/user/api-key")
    ApiKeyView getApiKey();

    @POST
    @Path("/api/user/api-key")
    @ResponseStatus(HTTPStatus.OK)
    GenerateApiKeyResponse generateApiKey();

    @POST
    @Path("/api/user/change-password")
    void changePassword(ChangePasswordRequest request);
}
