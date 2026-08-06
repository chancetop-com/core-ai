package ai.core.server.apiuser;

import ai.core.api.server.apiuser.ApiUserWebService;
import ai.core.api.server.apiuser.request.CreateApiUserRequest;
import ai.core.api.server.apiuser.request.CreateKeyRequest;
import ai.core.api.server.apiuser.request.RenewKeyRequest;
import ai.core.api.server.apiuser.request.UpdateApiUserConfigRequest;
import ai.core.api.server.apiuser.request.UsageQueryRequest;
import ai.core.api.server.apiuser.response.ApiUserView;
import ai.core.api.server.apiuser.response.CreateApiUserResponse;
import ai.core.api.server.apiuser.response.CreateKeyResponse;
import ai.core.api.server.apiuser.response.ListKeysView;
import ai.core.api.server.apiuser.response.RenewKeyResponse;
import ai.core.api.server.apiuser.response.UsageView;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

/**
 * Management surface for API users, authenticated with a management key (scope=manage).
 * The authenticated user is the manager; all resources are ownership-checked against it.
 *
 * @author stephen
 */
public class ApiUserWebServiceImpl implements ApiUserWebService {
    @Inject
    WebContext webContext;
    @Inject
    ApiUserService apiUserService;
    @Inject
    ApiUserKeyService apiUserKeyService;
    @Inject
    ApiUserUsageService apiUserUsageService;

    @Override
    public CreateApiUserResponse create(CreateApiUserRequest request) {
        var managerUserId = AuthContext.userId(webContext);
        var user = apiUserService.createApiUser(managerUserId, request.externalId, request.name);
        ActionLogContext.put("user_id", user.id);
        var response = new CreateApiUserResponse();
        response.userId = user.id;
        response.status = user.status;
        return response;
    }

    @Override
    public ApiUserView get(String userId) {
        var managerUserId = AuthContext.userId(webContext);
        return apiUserService.toView(apiUserService.getApiUser(managerUserId, userId));
    }

    @Override
    public ApiUserView updateConfig(String userId, UpdateApiUserConfigRequest request) {
        var managerUserId = AuthContext.userId(webContext);
        return apiUserService.toView(apiUserService.updateConfig(managerUserId, userId, request));
    }

    @Override
    public UsageView usage(String userId, UsageQueryRequest request) {
        var managerUserId = AuthContext.userId(webContext);
        apiUserService.getApiUser(managerUserId, userId);
        return apiUserUsageService.usage(userId, request.from, request.to);
    }

    @Override
    public CreateKeyResponse createKey(String userId, CreateKeyRequest request) {
        var managerUserId = AuthContext.userId(webContext);
        return apiUserKeyService.issueKey(managerUserId, userId, request);
    }

    @Override
    public ListKeysView listKeys(String userId) {
        var managerUserId = AuthContext.userId(webContext);
        return apiUserKeyService.listKeys(managerUserId, userId);
    }

    @Override
    public RenewKeyResponse renewKey(String keyId, RenewKeyRequest request) {
        var managerUserId = AuthContext.userId(webContext);
        return apiUserKeyService.renewKey(managerUserId, keyId, request);
    }

    @Override
    public void expireKey(String keyId) {
        var managerUserId = AuthContext.userId(webContext);
        apiUserKeyService.expireKey(managerUserId, keyId);
    }
}
