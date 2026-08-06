package ai.core.server.apiuser;

import ai.core.api.server.apiuser.AdminApiUserWebService;
import ai.core.api.server.apiuser.request.CreateApiUserRequest;
import ai.core.api.server.apiuser.request.UpdateStatusRequest;
import ai.core.api.server.apiuser.response.AdminApiUserView;
import ai.core.api.server.apiuser.response.CreateApiUserResponse;
import ai.core.api.server.apiuser.response.ListApiUsersResponse;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

/**
 * Admin surface for manager-type API users (business system subjects).
 * Creates the subject and returns the cmk_ management key once; rotation/status are also admin-only.
 *
 * @author stephen
 */
public class AdminApiUserWebServiceImpl implements AdminApiUserWebService {
    @Inject
    WebContext webContext;
    @Inject
    ApiUserService apiUserService;
    @Inject
    ApiUserKeyService apiUserKeyService;

    @Override
    public CreateApiUserResponse create(CreateApiUserRequest request) {
        if (request.name == null || request.name.isBlank()) throw new BadRequestException("name is required");
        var manager = apiUserService.createManager(request.name);
        ActionLogContext.put("user_id", manager.id);
        var response = new CreateApiUserResponse();
        response.userId = manager.id;
        response.status = manager.status;
        // management key is returned once at creation and handed to the business system offline
        response.apiKey = apiUserKeyService.issueManagementKey(manager.id).key;
        return response;
    }

    @Override
    public ListApiUsersResponse list() {
        var response = new ListApiUsersResponse();
        response.users = apiUserService.listApiUsers(AuthContext.userId(webContext)).stream()
                .map(user -> {
                    var view = new AdminApiUserView();
                    view.userId = user.id;
                    view.name = user.name;
                    view.status = user.status;
                    view.createdAt = user.createdAt;
                    return view;
                })
                .toList();
        return response;
    }

    @Override
    public CreateApiUserResponse rotateKey(String id) {
        var manager = apiUserService.getApiUser(AuthContext.userId(webContext), id);
        if (manager.ownerId != null) throw new BadRequestException("only manager users can rotate keys");
        apiUserKeyService.revokeManageKeys(id);
        var response = new CreateApiUserResponse();
        response.userId = id;
        response.status = manager.status;
        response.apiKey = apiUserKeyService.issueManagementKey(id).key;
        return response;
    }

    @Override
    public void updateStatus(String id, UpdateStatusRequest request) {
        apiUserService.updateStatus(AuthContext.userId(webContext), id, request.status);
    }
}
