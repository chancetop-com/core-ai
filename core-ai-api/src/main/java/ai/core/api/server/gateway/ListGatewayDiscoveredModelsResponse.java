package ai.core.api.server.gateway;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListGatewayDiscoveredModelsResponse {
    @Property(name = "providerId")
    public String providerId;

    @Property(name = "providerName")
    public String providerName;

    @Property(name = "models")
    public List<GatewayDiscoveredModelView> models;
}
