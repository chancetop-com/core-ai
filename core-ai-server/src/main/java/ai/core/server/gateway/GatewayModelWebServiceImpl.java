package ai.core.server.gateway;

import ai.core.api.server.gateway.GatewayModelRequest;
import ai.core.api.server.gateway.GatewayModelView;
import ai.core.api.server.gateway.GatewayModelWebService;
import ai.core.api.server.gateway.ImportGatewayModelsRequest;
import ai.core.api.server.gateway.ListGatewayAvailableModelsResponse;
import ai.core.api.server.gateway.ListGatewayDiscoveredModelsResponse;
import ai.core.api.server.gateway.ListGatewayModelsResponse;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

import java.util.HashMap;

/**
 * @author stephen
 */
public class GatewayModelWebServiceImpl implements GatewayModelWebService {
    @Inject
    GatewayModelService gatewayModelService;

    @Inject
    GatewayModelDiscoveryService gatewayModelDiscoveryService;

    @Inject
    WebContext webContext;

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public ListGatewayModelsResponse list() {
        return gatewayModelService.list(userId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public ListGatewayAvailableModelsResponse listAvailable() {
        return gatewayModelService.listAvailable();
    }

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public GatewayModelView create(GatewayModelRequest request) {
        withFields(request);
        return gatewayModelService.create(request, userId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public GatewayModelView update(String id, GatewayModelRequest request) {
        withFields(request);
        return gatewayModelService.update(id, request, userId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public void delete(String id) {
        gatewayModelService.delete(id, userId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public GatewayModelView markDefault(String id) {
        return gatewayModelService.markDefault(id, userId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public ListGatewayDiscoveredModelsResponse discover(String id) {
        return gatewayModelDiscoveryService.discover(id, userId());
    }

    @Override
    @PermissionsRequired(PermissionCodes.GATEWAY_MANAGE)
    public ListGatewayModelsResponse importModels(String id, ImportGatewayModelsRequest request) {
        return gatewayModelService.importModels(id, request, userId());
    }

    // core-ng request bean cannot distinguish "field absent" from "field set to null",
    // which matters for partial updates; recover the actually-present fields from the raw body.
    private void withFields(GatewayModelRequest request) {
        try {
            var body = webContext.request().body().orElseThrow(() -> new BadRequestException("body is required"));
            var node = GatewayJson.MAPPER.readTree(body);
            if (!node.isObject()) throw new BadRequestException("request body must be an object");
            var fields = new HashMap<String, Boolean>();
            node.fieldNames().forEachRemaining(name -> fields.put(name, Boolean.TRUE));
            request.fields = fields;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("invalid request body: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private String userId() {
        return AuthContext.userId(webContext);
    }
}
