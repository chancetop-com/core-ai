package ai.core.server.gateway;

import ai.core.api.server.gateway.DeleteGatewayProviderRequest;
import ai.core.api.server.gateway.GatewayProviderRequest;
import ai.core.api.server.gateway.GatewayProviderView;
import ai.core.api.server.gateway.GatewayProviderWebService;
import ai.core.api.server.gateway.ListGatewayProvidersResponse;
import ai.core.api.server.gateway.TestGatewayProviderResponse;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class GatewayProviderWebServiceImpl implements GatewayProviderWebService {
    @Inject
    GatewayProviderService gatewayProviderService;

    @Inject
    WebContext webContext;

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public ListGatewayProvidersResponse list() {
        return gatewayProviderService.list(userId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public GatewayProviderView create(GatewayProviderRequest request) {
        return gatewayProviderService.create(request, userId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public GatewayProviderView update(String id, GatewayProviderRequest request) {
        return gatewayProviderService.update(id, request, userId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public void delete(String id, DeleteGatewayProviderRequest request) {
        var cascadeModels = "true".equalsIgnoreCase(request.cascade);
        gatewayProviderService.delete(id, userId(), cascadeModels);
    }

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public TestGatewayProviderResponse test(String id) {
        return gatewayProviderService.test(id, userId());
    }

    private String userId() {
        return AuthContext.userId(webContext);
    }
}
