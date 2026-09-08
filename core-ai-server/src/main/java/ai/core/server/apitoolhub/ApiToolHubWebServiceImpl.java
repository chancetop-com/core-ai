package ai.core.server.apitoolhub;

import ai.core.api.server.ApiToolHubWebService;
import ai.core.api.server.apitoolhub.ApiToolHubAppsResponse;
import ai.core.api.server.apitoolhub.ApiToolHubLookupRequest;
import ai.core.api.server.apitoolhub.ApiToolHubLookupResponse;
import ai.core.api.server.apitoolhub.ApiToolHubOperationDetail;
import ai.core.api.server.apitoolhub.ApiToolHubSearchRequest;
import ai.core.api.server.apitoolhub.ApiToolHubSearchResponse;
import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class ApiToolHubWebServiceImpl implements ApiToolHubWebService {
    @Inject
    ApiToolHubService hubService;
    @Inject
    WebContext webContext;

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_CALL)
    public ApiToolHubAppsResponse apps() {
        return hubService.apps();
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_CALL)
    public ApiToolHubSearchResponse search(ApiToolHubSearchRequest request) {
        var effective = request != null ? request : new ApiToolHubSearchRequest();
        return hubService.search(effective.query, effective.app, effective.service, effective.limit);
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_CALL)
    public ApiToolHubLookupResponse lookup(ApiToolHubLookupRequest request) {
        return hubService.lookup(request != null ? request.toolName : null);
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_CALL)
    public ApiToolHubOperationDetail describe(String app, String service, String operation) {
        return hubService.describe(app, service, operation);
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_CALL)
    public HubCallResponse call(String app, String service, String operation, HubCallRequest request) {
        var source = webContext.request().header("X-Core-AI-Client").orElse("unknown");
        return hubService.call(AuthContext.userId(webContext), source, app, service, operation, request);
    }
}
