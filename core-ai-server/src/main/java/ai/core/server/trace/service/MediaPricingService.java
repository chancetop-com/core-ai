package ai.core.server.trace.service;

import ai.core.llm.LLMModelContextRegistry;
import ai.core.media.domain.Usage;
import ai.core.server.domain.GatewayModelConfig;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

/**
 * Media generation pricing (design docs/cn/design-media-cost-recording.md). Same priority order as
 * {@link ModelPricingService}: explicit gateway model price > upstream-reported cost > catalog estimate.
 * The LLM text cost pipeline (spans/traces) is untouched — this service only prices media jobs.
 *
 * @author stephen
 */
public class MediaPricingService {
    @Inject
    MongoCollection<GatewayModelConfig> gatewayModelCollection;

    public MediaPrice resolveImage(String requestedModel, String resolvedModel, Usage usage, int imageCount) {
        var gatewayModel = gatewayModel(requestedModel);
        if (gatewayModel != null && gatewayModel.imagePricePerImage != null && imageCount > 0) {
            return new MediaPrice(gatewayModel.imagePricePerImage * imageCount, "gateway_model", gatewayModel.modelId,
                    (double) imageCount, "image");
        }
        if (usage != null && usage.upstreamCostUsd() != null) {
            return new MediaPrice(usage.upstreamCostUsd(), "upstream", null, null, null);
        }
        return catalogImagePrice(resolvedModel, usage, imageCount, requestedModel);
    }

    public MediaPrice resolveVideo(String requestedModel, String resolvedModel, Integer seconds,
                                   Double creditsConsumed, Double creditUsdRate, Double upstreamCostUsd) {
        var gatewayModel = gatewayModel(requestedModel);
        if (gatewayModel != null && gatewayModel.videoPricePerSecond != null && seconds != null) {
            return new MediaPrice(gatewayModel.videoPricePerSecond * seconds, "gateway_model", gatewayModel.modelId,
                    (double) seconds, "second");
        }
        if (upstreamCostUsd != null) {
            return new MediaPrice(upstreamCostUsd, "upstream", null, null, null);
        }
        if (creditsConsumed != null && creditUsdRate != null) {
            return new MediaPrice(creditsConsumed * creditUsdRate, "upstream", null, creditsConsumed, "credit");
        }
        return catalogVideoPrice(resolvedModel, seconds, requestedModel);
    }

    private MediaPrice catalogImagePrice(String model, Usage usage, int imageCount, String fallbackModel) {
        var estimate = estimateImage(model, usage, imageCount);
        if (estimate == null && fallbackModel != null && !fallbackModel.equals(model)) {
            estimate = estimateImage(fallbackModel, usage, imageCount);
        }
        return estimate == null ? MediaPrice.unavailable() : new MediaPrice(estimate.costUsd(), "model_catalog",
                estimate.pricingModelId(), estimate.units(), estimate.unitType());
    }

    private LLMModelContextRegistry.MediaCostEstimate estimateImage(String model, Usage usage, int imageCount) {
        if (usage == null) return LLMModelContextRegistry.getInstance().estimateImageCost(model, null, null, null, imageCount);
        return LLMModelContextRegistry.getInstance().estimateImageCost(model, usage.inputTextTokens(), usage.inputImageTokens(),
                usage.outputTokens(), imageCount);
    }

    private MediaPrice catalogVideoPrice(String model, Integer seconds, String fallbackModel) {
        var estimate = LLMModelContextRegistry.getInstance().estimateVideoCost(model, seconds);
        if (estimate == null && fallbackModel != null && !fallbackModel.equals(model)) {
            estimate = LLMModelContextRegistry.getInstance().estimateVideoCost(fallbackModel, seconds);
        }
        return estimate == null ? MediaPrice.unavailable() : new MediaPrice(estimate.costUsd(), "model_catalog",
                estimate.pricingModelId(), estimate.units(), estimate.unitType());
    }

    private GatewayModelConfig gatewayModel(String model) {
        if (model == null || model.isBlank()) return null;
        var models = gatewayModelCollection.find(Filters.eq("model_id", model));
        if (!models.isEmpty()) return models.getFirst();
        return null;
    }

    public record MediaPrice(Double costUsd, String source, String pricingModelId, Double units, String unitType) {
        static MediaPrice unavailable() {
            return new MediaPrice(null, "unavailable", null, null, null);
        }
    }
}
