package ai.core.server.gateway;

import ai.core.api.server.hubmodels.HubModelWebService;
import ai.core.api.server.hubmodels.ListHubModelsResponse;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class HubModelWebServiceImpl implements HubModelWebService {
    @Inject
    HubModelService hubModelService;
    @Inject
    WebContext webContext;

    @Override
    @PermissionsRequired({PermissionCodes.MCP_CALL, PermissionCodes.APITOOL_CALL, PermissionCodes.CHAT_USE})
    public ListHubModelsResponse models() {
        return hubModelService.models(AuthContext.userId(webContext));
    }
}
