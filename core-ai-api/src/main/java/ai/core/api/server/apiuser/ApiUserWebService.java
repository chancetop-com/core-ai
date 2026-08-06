package ai.core.api.server.apiuser;

import ai.core.api.server.apiuser.request.CreateApiUserRequest;
import ai.core.api.server.apiuser.response.ApiUserView;
import ai.core.api.server.apiuser.response.CreateApiUserResponse;
import ai.core.api.server.apiuser.response.CreateKeyResponse;
import ai.core.api.server.apiuser.response.RenewKeyResponse;
import ai.core.api.server.apiuser.response.ListKeysView;
import ai.core.api.server.apiuser.response.UsageView;
import ai.core.api.server.apiuser.request.UpdateApiUserConfigRequest;
import ai.core.api.server.apiuser.request.UsageQueryRequest;
import ai.core.api.server.apiuser.request.CreateKeyRequest;
import ai.core.api.server.apiuser.request.RenewKeyRequest;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * Management surface for API users, invoked by business system backends with a management key (cmk_).
 *
 * @author stephen
 */
public interface ApiUserWebService {
    @POST
    @Path("/api/api-users")
    @ResponseStatus(HTTPStatus.OK)
    CreateApiUserResponse create(CreateApiUserRequest request);

    @GET
    @Path("/api/api-users/:userId")
    ApiUserView get(@PathParam("userId") String userId);

    @PUT
    @Path("/api/api-users/:userId/config")
    @ResponseStatus(HTTPStatus.OK)
    ApiUserView updateConfig(@PathParam("userId") String userId, UpdateApiUserConfigRequest request);

    @GET
    @Path("/api/api-users/:userId/usage")
    UsageView usage(@PathParam("userId") String userId, UsageQueryRequest request);

    @POST
    @Path("/api/api-users/:userId/keys")
    @ResponseStatus(HTTPStatus.OK)
    CreateKeyResponse createKey(@PathParam("userId") String userId, CreateKeyRequest request);

    @GET
    @Path("/api/api-users/:userId/keys")
    ListKeysView listKeys(@PathParam("userId") String userId);

    @POST
    @Path("/api/api-users/keys/:keyId/renew")
    @ResponseStatus(HTTPStatus.OK)
    RenewKeyResponse renewKey(@PathParam("keyId") String keyId, RenewKeyRequest request);

    @POST
    @Path("/api/api-users/keys/:keyId/expire")
    @ResponseStatus(HTTPStatus.OK)
    void expireKey(@PathParam("keyId") String keyId);
}
