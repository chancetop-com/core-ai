package ai.core.server.trace.service;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.server.domain.GatewayModelConfig;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

/**
 * @author Stephen
 */
public class ModelPricingService {
    @Inject
    MongoCollection<GatewayModelConfig> gatewayModelCollection;

    public Price resolve(String model, Long inputTokens, Long outputTokens, Long cachedTokens) {
        var gatewayModel = gatewayModel(model);
        if (gatewayModel != null && gatewayModel.inputPricePer1MTokens != null && gatewayModel.outputPricePer1MTokens != null) {
            double inputCost = safeLong(inputTokens) * gatewayModel.inputPricePer1MTokens / 1_000_000D;
            double outputCost = safeLong(outputTokens) * gatewayModel.outputPricePer1MTokens / 1_000_000D;
            return new Price(inputCost + outputCost, "gateway_model", gatewayModel.modelId,
                    gatewayModel.inputPricePer1MTokens, gatewayModel.outputPricePer1MTokens);
        }

        var costUsd = LLMModelContextRegistry.getInstance().estimateCostUsd(model,
                safeLong(inputTokens), safeLong(outputTokens), safeLong(cachedTokens));
        return costUsd == null ? Price.unavailable() : new Price(costUsd, "model_catalog", model, null, null);
    }

    private GatewayModelConfig gatewayModel(String model) {
        if (model == null || model.isBlank()) return null;
        var models = gatewayModelCollection.find(Filters.eq("model_id", model));
        if (!models.isEmpty()) return models.getFirst();
        return null;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    public record Price(Double costUsd, String source, String modelId, Double inputPricePer1MTokens, Double outputPricePer1MTokens) {
        static Price unavailable() {
            return new Price(null, "unavailable", null, null, null);
        }
    }
}
