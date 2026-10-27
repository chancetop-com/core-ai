package ai.core.server.gateway;

import ai.core.api.server.gateway.GatewayAvailableModelView;
import ai.core.api.server.hubmodels.ListHubModelsResponse;
import core.framework.inject.Inject;

/**
 * Chat-model catalog for hub clients (core-ai-cli / desktop model pickers). The list is the
 * projection of the same access policy that guards requests: when per-user model access tightens,
 * the filter belongs here AND in routing, sharing one policy function so the two never drift.
 *
 * @author stephen
 */
public class HubModelService {
    // the responses endpoint is a chat surface too: the gateway falls back to it for models that
    // only serve responses (e.g. KIE gpt-6.1-sol, Azure gpt-6.1-sol), so those models must stay
    // selectable in the CLI/desktop model pickers
    private static boolean supportsChat(GatewayAvailableModelView view) {
        return view.endpointTypes == null || view.endpointTypes.isEmpty()
                || view.endpointTypes.contains(GatewayModelService.ENDPOINT_CHAT_COMPLETIONS)
                || view.endpointTypes.contains(GatewayModelService.ENDPOINT_RESPONSES);
    }

    @Inject
    GatewayRoutingEngine gatewayRoutingEngine;

    public ListHubModelsResponse models(String userId) {
        var response = new ListHubModelsResponse();
        response.models = gatewayRoutingEngine.availableModels().stream()
                .filter(HubModelService::supportsChat)
                .toList();
        return response;
    }
}
