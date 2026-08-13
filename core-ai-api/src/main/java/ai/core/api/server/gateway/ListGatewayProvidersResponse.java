package ai.core.api.server.gateway;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListGatewayProvidersResponse {
    @Property(name = "providers")
    public List<GatewayProviderView> providers;
}
