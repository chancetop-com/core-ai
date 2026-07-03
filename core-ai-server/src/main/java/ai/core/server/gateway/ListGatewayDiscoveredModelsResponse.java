package ai.core.server.gateway;

import java.util.List;

public class ListGatewayDiscoveredModelsResponse {
    public String providerId;
    public String providerName;
    public List<GatewayDiscoveredModelView> models;
}
