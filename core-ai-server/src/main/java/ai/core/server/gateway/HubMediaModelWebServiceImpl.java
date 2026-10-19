package ai.core.server.gateway;

import ai.core.api.server.hubmodels.HubMediaModelWebService;
import ai.core.api.server.hubmodels.ListHubMediaModelsResponse;
import ai.core.server.rbac.PermissionsBypass;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
@PermissionsBypass
public class HubMediaModelWebServiceImpl implements HubMediaModelWebService {
    @Inject
    HubMediaModelService hubMediaModelService;

    @Override
    public ListHubMediaModelsResponse models() {
        return hubMediaModelService.models();
    }
}
