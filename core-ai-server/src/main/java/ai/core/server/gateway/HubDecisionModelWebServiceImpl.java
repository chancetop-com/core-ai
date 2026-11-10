package ai.core.server.gateway;

import ai.core.api.server.hubmodels.HubDecisionModelWebService;
import ai.core.api.server.hubmodels.ListHubDecisionModelsResponse;
import ai.core.server.rbac.PermissionsBypass;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
@PermissionsBypass
public class HubDecisionModelWebServiceImpl implements HubDecisionModelWebService {
    @Inject
    HubDecisionModelService hubDecisionModelService;

    @Override
    public ListHubDecisionModelsResponse models() {
        return hubDecisionModelService.models();
    }
}
