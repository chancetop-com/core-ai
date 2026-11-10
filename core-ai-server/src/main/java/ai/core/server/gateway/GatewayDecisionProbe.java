package ai.core.server.gateway;

import ai.core.api.server.gateway.TestGatewayProviderResponse;
import ai.core.decision.DecisionQuestion;
import ai.core.decision.DecisionRequest;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.web.exception.BadRequestException;

import java.util.List;

import static ai.core.server.gateway.GatewaySupport.isBlank;

/**
 * Connectivity probe for decision providers. They expose no /models listing, so the smallest possible
 * noul question verifies the credential, the full System One path and the model deployment in one call.
 *
 * @author stephen
 */
final class GatewayDecisionProbe {
    static boolean isDecisionProvider(GatewayProviderConfig provider, GatewayModelStore modelStore) {
        return !isBlank(provider.decisionProtocol)
                || !isBlank(provider.defaultDecisionModel)
                || !decisionModels(provider.id, modelStore).isEmpty();
    }

    static void test(GatewayProviderConfig provider, String apiKey, GatewayModelStore modelStore,
                     TestGatewayProviderResponse result) {
        var model = probeModel(provider, modelStore);
        var decisionProvider = new DecisionProviderAdapterFactory().create(provider, apiKey == null ? "" : apiKey);
        var response = decisionProvider.decide(new DecisionRequest(model, "ping",
                List.of(DecisionQuestion.noul("reachable", "Is this a reachable decision endpoint?", null))));
        result.ok = Boolean.TRUE;
        result.status = "ok";
        result.message = "Connected; decision answered" + (response.model() == null ? "" : ", model=" + response.model());
    }

    private static List<GatewayModelConfig> decisionModels(String providerId, GatewayModelStore modelStore) {
        if (modelStore == null) return List.of();
        return modelStore.listByProviderId(providerId).stream()
                .filter(model -> model.endpointTypes != null
                        && model.endpointTypes.contains(GatewayModelService.ENDPOINT_DECISION))
                .toList();
    }

    private static String probeModel(GatewayProviderConfig provider, GatewayModelStore modelStore) {
        var selected = decisionModels(provider.id, modelStore).stream()
                .filter(model -> Boolean.TRUE.equals(model.enabled) && !isBlank(model.upstreamModel))
                .map(model -> model.upstreamModel)
                .findFirst();
        if (selected.isPresent()) return selected.get();
        if (!isBlank(provider.defaultDecisionModel)) return provider.defaultDecisionModel;
        throw new BadRequestException("decision provider requires a decision model on the provider or a defaultDecisionModel");
    }

    private GatewayDecisionProbe() {
    }
}
