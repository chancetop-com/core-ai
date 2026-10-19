package ai.core.server.gateway;

import ai.core.api.server.hubmodels.HubMediaModelView;
import ai.core.api.server.hubmodels.ListHubMediaModelsResponse;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Media-model catalog for hub clients. Each endpoint list keeps the routing engine's order after removing
 * duplicate failover registrations, so "the first model" is also what the gateway routes to when a request
 * omits the model.
 *
 * @author stephen
 */
public class HubMediaModelService {
    @Inject
    GatewayRoutingEngine gatewayRoutingEngine;

    public ListHubMediaModelsResponse models() {
        var response = new ListHubMediaModelsResponse();
        response.imageModels = hints(GatewayEndpointType.IMAGE_GENERATION);
        response.imageEditModels = hints(GatewayEndpointType.IMAGE_EDIT);
        response.videoModels = hints(GatewayEndpointType.VIDEO_GENERATION);
        return response;
    }

    private List<HubMediaModelView> hints(GatewayEndpointType endpoint) {
        var seen = new HashSet<String>();
        var views = new ArrayList<HubMediaModelView>();
        for (var hint : gatewayRoutingEngine.mediaModelHints(endpoint)) {
            if (hint.modelId() == null || !seen.add(hint.modelId())) continue;
            var view = new HubMediaModelView();
            view.modelId = hint.modelId();
            view.displayName = hint.displayName();
            view.providerName = hint.providerName();
            view.upstreamModel = hint.upstreamModel();
            views.add(view);
        }
        return views;
    }
}
