package ai.core.api.server.hubmodels;

import ai.core.api.server.gateway.GatewayAvailableModelView;
import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListHubModelsResponse {
    @Property(name = "models")
    public List<GatewayAvailableModelView> models;
}
