package ai.core.api.server;

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

    @POST
    @Path("/api/user/api-key")
    @ResponseStatus(HTTPStatus.OK)
    GenerateApiKeyResponse generateApiKey();
}
