package ai.core.server.cataloghub;

import ai.core.api.server.HubCatalogWebService;
import ai.core.api.server.hubcatalog.HubCatalogResponse;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class HubCatalogWebServiceImpl implements HubCatalogWebService {
    @Inject
    HubCatalogService hubCatalogService;

    @Override
    @PermissionsRequired({PermissionCodes.MCP_CALL, PermissionCodes.APITOOL_CALL, PermissionCodes.CHAT_USE})
    public HubCatalogResponse catalog() {
        return hubCatalogService.catalog();
    }
}
