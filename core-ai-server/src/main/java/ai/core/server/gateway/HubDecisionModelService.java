package ai.core.server.gateway;

import ai.core.api.server.hubmodels.HubDecisionModelView;
import ai.core.api.server.hubmodels.ListHubDecisionModelsResponse;
import core.framework.inject.Inject;

/**
 * Decision-model catalog for hub clients (core-ai-cli / desktop). The list keeps gateway priority
 * order, so its first entry is the model a request without a model routes to.
 *
 * @author stephen
 */
public class HubDecisionModelService {
    @Inject
    GatewayRoutingEngine gatewayRoutingEngine;

    public ListHubDecisionModelsResponse models() {
        var response = new ListHubDecisionModelsResponse();
        response.models = gatewayRoutingEngine.decisionModelHints().stream().map(hint -> {
            var view = new HubDecisionModelView();
            view.modelId = hint.modelId();
            view.displayName = hint.displayName();
            view.providerName = hint.providerName();
            view.upstreamModel = hint.upstreamModel();
            return view;
        }).toList();
        return response;
    }
}
