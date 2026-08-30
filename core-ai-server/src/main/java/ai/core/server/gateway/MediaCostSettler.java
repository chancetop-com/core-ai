package ai.core.server.gateway;

import ai.core.media.domain.Usage;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.server.domain.MediaJob;
import ai.core.server.trace.service.MediaPricingService;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects media pricing inputs (job context + upstream usage/status) and delegates to
 * {@link MediaPricingService}. Settlement failures must never break the generation flow —
 * callers wrap the settlement call.
 *
 * @author stephen
 */
public class MediaCostSettler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaCostSettler.class);

    @Inject
    MediaPricingService pricingService;
    @Inject
    GatewayRoutingEngine routingEngine;

    public MediaPricingService.MediaPrice settleVideo(MediaJob job, VideoStatusResponse status) {
        var creditUsdRate = creditUsdRate(job.providerId);
        return pricingService.resolveVideo(job.requestedModel, job.resolvedModel, job.requestedSeconds,
                status.creditsConsumed(), creditUsdRate, status.upstreamCostUsd());
    }

    public MediaPricingService.MediaPrice settleImage(String requestedModel, String resolvedModel, Usage usage, int imageCount) {
        return pricingService.resolveImage(requestedModel, resolvedModel, usage, imageCount);
    }

    private Double creditUsdRate(String providerId) {
        try {
            var provider = routingEngine.jobProvider(providerId);
            return provider == null ? null : provider.creditUsdRate;
        } catch (RuntimeException e) {
            LOGGER.warn("credit rate lookup failed, provider={}", providerId, e);
            return null;
        }
    }
}
