package ai.core.api.server.gateway;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListGatewayAvailableModelsResponse {
    @Property(name = "models")
    public List<GatewayAvailableModelView> models;
}
