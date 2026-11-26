package ai.core.server.trace.service;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.server.domain.GatewayModelConfig;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;

/**
 * @author Stephen
 */
public class ModelPricingService {
    @Inject
    ai.core.server.gateway.GatewayModelStore gatewayModelStore;

    // Gateway Model configuration price (cached input split + optional peak-hour multiplier); null when not configured.
    // When the serving provider is known, price that provider's row; an alias may be registered on
    // several providers, and each row carries its own prices.
    Price resolveGatewayModel(String model, String providerId, Long inputTokens, Long outputTokens, Long cachedTokens,
                              ZonedDateTime startedAt) {
        var gatewayModel = gatewayModel(model, providerId);
        if (gatewayModel == null || gatewayModel.inputPricePer1MTokens == null || gatewayModel.outputPricePer1MTokens == null) {
            return null;
        }
        var cached = Math.min(Math.max(safeLong(cachedTokens), 0), safeLong(inputTokens));
        var uncached = safeLong(inputTokens) - cached;
        var cacheReadPrice = gatewayModel.cacheReadInputPricePer1MTokens != null
                ? gatewayModel.cacheReadInputPricePer1MTokens
                : gatewayModel.inputPricePer1MTokens;
        double inputCost = (uncached * gatewayModel.inputPricePer1MTokens + cached * cacheReadPrice) / 1_000_000D;
        double outputCost = safeLong(outputTokens) * gatewayModel.outputPricePer1MTokens / 1_000_000D;
        var multiplier = peakMultiplier(gatewayModel.peakPriceMultiplier, startedAt);
        return new Price((inputCost + outputCost) * multiplier, "gateway_model", gatewayModel.modelId,
                gatewayModel.inputPricePer1MTokens, gatewayModel.outputPricePer1MTokens);
    }

    // Resolution order: explicit Gateway Model pricing > upstream-reported attribute cost > catalog estimate.
    public Price resolve(String model, Long inputTokens, Long outputTokens, Long cachedTokens, ZonedDateTime startedAt, Double upstreamCost) {
        return resolve(model, null, inputTokens, outputTokens, cachedTokens, startedAt, upstreamCost);
    }

    // A request that failed over to another gateway model is priced by the model that actually served
    // it; the requested alias is only the fallback when the resolved model has no gateway price.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Price resolve(String model, String resolvedModel, String providerId, Long inputTokens, Long outputTokens,
                         Long cachedTokens, ZonedDateTime startedAt, Double upstreamCost) {
        if (resolvedModel != null && !resolvedModel.isBlank() && !resolvedModel.equals(model)) {
            var resolvedPrice = resolveGatewayModel(resolvedModel, providerId, inputTokens, outputTokens, cachedTokens, startedAt);
            if (resolvedPrice != null) return resolvedPrice;
        }
        return resolve(model, providerId, inputTokens, outputTokens, cachedTokens, startedAt, upstreamCost);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public Price resolve(String model, String providerId, Long inputTokens, Long outputTokens, Long cachedTokens,
                         ZonedDateTime startedAt, Double upstreamCost) {
        var gatewayPrice = resolveGatewayModel(model, providerId, inputTokens, outputTokens, cachedTokens, startedAt);
        if (gatewayPrice != null) return gatewayPrice;
        if (upstreamCost != null) return new Price(upstreamCost, "upstream", null, null, null);
        return resolveCatalog(model, inputTokens, outputTokens, cachedTokens, startedAt);
    }

    Price resolveCatalog(String model, Long inputTokens, Long outputTokens, Long cachedTokens, ZonedDateTime startedAt) {
        var when = startedAt == null ? null : startedAt.toInstant();
        var costUsd = when == null
                ? LLMModelContextRegistry.getInstance().estimateCostUsd(model,
                        safeLong(inputTokens), safeLong(outputTokens), safeLong(cachedTokens))
                : LLMModelContextRegistry.getInstance().estimateCostUsd(model,
                        safeLong(inputTokens), safeLong(outputTokens), safeLong(cachedTokens), when);
        return costUsd == null ? Price.unavailable() : new Price(costUsd, "model_catalog", model, null, null);
    }

    private double peakMultiplier(Double configured, ZonedDateTime startedAt) {
        if (configured == null || configured <= 0 || startedAt == null) return 1.0;
        return LLMModelContextRegistry.isPeakHour(startedAt.toInstant()) ? configured : 1.0;
    }

    private GatewayModelConfig gatewayModel(String model, String providerId) {
        if (model == null || model.isBlank()) return null;
        if (providerId != null && !providerId.isBlank()) {
            var providerModel = gatewayModelStore.listByProviderId(providerId).stream()
                    .filter(candidate -> model.equals(candidate.modelId))
                    .findFirst();
            if (providerModel.isPresent()) return providerModel.get();
        }
        var models = gatewayModelStore.listByModelId(model);
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
