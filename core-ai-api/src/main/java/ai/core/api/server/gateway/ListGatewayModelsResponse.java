package ai.core.api.server.gateway;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListGatewayModelsResponse {
    @Property(name = "models")
    public List<GatewayModelView> models;
}
