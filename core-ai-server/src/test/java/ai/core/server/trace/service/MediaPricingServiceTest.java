package ai.core.server.trace.service;

import ai.core.media.domain.Usage;
import ai.core.server.domain.GatewayModelConfig;
import com.mongodb.client.model.Filters;
import core.framework.mongo.MongoCollection;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Media pricing priority: explicit gateway model price > upstream-reported cost > catalog estimate.
 *
 * @author stephen
 */
class MediaPricingServiceTest {
    private MediaPricingService service;
    private MongoCollection<GatewayModelConfig> models;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new MediaPricingService();
        models = (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);
        service.gatewayModelCollection = models;
    }

    private MediaPricingService.VideoPricingRequest videoRequest(String requestedModel, String resolvedModel, Integer seconds,
                                                                 Double creditsConsumed, Double creditUsdRate, Double upstreamCostUsd) {
        return new MediaPricingService.VideoPricingRequest(requestedModel, resolvedModel, seconds,
                creditsConsumed, creditUsdRate, upstreamCostUsd, null);
    }

    private GatewayModelConfig gatewayModel(String modelId, Double imagePrice, Double videoPricePerSecond) {
        var model = new GatewayModelConfig();
        model.id = "cfg-1";
        model.modelId = modelId;
        model.imagePricePerImage = imagePrice;
        model.videoPricePerSecond = videoPricePerSecond;
        return model;
    }

    @Test
    void resolveImagePrefersGatewayModelPrice() {
        when(models.find(any(Bson.class))).thenReturn(List.of(gatewayModel("gpt-image-2", 0.1, null)));

        var price = service.resolveImage("gpt-image-2", "gpt-image-2", new Usage(350, 2, null, 150, 200, 100, 50, null, null), 2);

        assertEquals(0.2, price.costUsd());
        assertEquals("gateway_model", price.source());
        assertEquals("gpt-image-2", price.pricingModelId());
        assertEquals(2.0, price.units());
        assertEquals("image", price.unitType());
    }

    @Test
    void resolveImagePrefersUpstreamCostOverCatalog() {
        when(models.find(any(Bson.class))).thenReturn(List.of());

        var price = service.resolveImage("gpt-image-2", "gpt-image-2", new Usage(350, 1, null, 150, 200, 100, 50, 0.42, null), 1);

        assertEquals(0.42, price.costUsd());
        assertEquals("upstream", price.source());
        assertNull(price.pricingModelId());
    }

    @Test
    void resolveImageFallsBackToCatalogTokenPricing() {
        when(models.find(any(Bson.class))).thenReturn(List.of());

        var price = service.resolveImage("gpt-image-2", "gpt-image-2", new Usage(350, 1, null, 150, 200, 100, 50, null, null), 1);

        assertEquals(100 * 5e-6 + 50 * 8e-6 + 200 * 3e-5, price.costUsd(), 1e-12);
        assertEquals("model_catalog", price.source());
        assertEquals("gpt-image-2", price.pricingModelId());
        assertEquals(200.0, price.units());
        assertEquals("token", price.unitType());
    }

    @Test
    void resolveImageReturnsUnavailableForUnknownModel() {
        when(models.find(any(Bson.class))).thenReturn(List.of());

        var price = service.resolveImage("no-such-model", "no-such-model", null, 1);

        assertNull(price.costUsd());
        assertEquals("unavailable", price.source());
    }

    @Test
    void resolveVideoPrefersGatewayModelPrice() {
        when(models.find(any(Bson.class))).thenReturn(List.of(gatewayModel("veo", null, 0.5)));

        var price = service.resolveVideo(videoRequest("veo", "veo-3.1-generate-001", 8, 10.0, 0.01, 0.2));

        assertEquals(4.0, price.costUsd());
        assertEquals("gateway_model", price.source());
        assertEquals(8.0, price.units());
        assertEquals("second", price.unitType());
    }

    @Test
    void resolveVideoPrefersUpstreamCostHeaderOverCredits() {
        when(models.find(any(Bson.class))).thenReturn(List.of());

        var price = service.resolveVideo(videoRequest("veo", "veo-3.1-generate-001", 8, 10.0, 0.01, 0.2));

        assertEquals(0.2, price.costUsd());
        assertEquals("upstream", price.source());
    }

    @Test
    void resolveVideoConvertsCreditsWithConfiguredRate() {
        when(models.find(any(Bson.class))).thenReturn(List.of());

        var price = service.resolveVideo(videoRequest("seedance", "bytedance/seedance-1-pro", 8, 10.0, 0.01, null));

        assertEquals(0.1, price.costUsd());
        assertEquals("upstream", price.source());
        assertEquals(10.0, price.units());
        assertEquals("credit", price.unitType());
    }

    @Test
    void resolveVideoFallsBackToCatalogPerSecond() {
        when(models.find(any(Bson.class))).thenReturn(List.of());

        var price = service.resolveVideo(videoRequest("veo-3.1-generate-001", "veo-3.1-generate-001", 8, null, null, null));

        assertEquals(3.2, price.costUsd(), 1e-12);
        assertEquals("model_catalog", price.source());
        assertEquals("gemini/veo-3.1-generate-001", price.pricingModelId());
        assertEquals(8.0, price.units());
        assertEquals("second", price.unitType());
    }

    @Test
    void resolveVideoReturnsUnavailableWithoutSecondsAndUpstreamData() {
        when(models.find(any(Bson.class))).thenReturn(List.of());

        var price = service.resolveVideo(videoRequest("veo-3.1-generate-001", "veo-3.1-generate-001", null, null, null, null));

        assertNull(price.costUsd());
        assertEquals("unavailable", price.source());
    }

    @Test
    void resolveVideoPricesGeminiOmniFromVideoOutputTokens() {
        when(models.find(any(Bson.class))).thenReturn(List.of());
        var usage = new Usage(33_832, null, null, 5_000, 28_832, null, null, null, 28_832);

        var price = service.resolveVideo(new MediaPricingService.VideoPricingRequest(
                "gemini-omni-flash-preview", "gemini-omni-flash-preview", null, null, null, null, usage));

        assertEquals(5_000 * 1.5e-6 + 28_832 * 1.75e-5, price.costUsd(), 1e-12);
        assertEquals("model_catalog", price.source());
        assertEquals("gemini-omni-flash-preview", price.pricingModelId());
        assertEquals("video_token", price.unitType());
    }

    @Test
    void resolveVideoPrefersUpstreamCostOverVideoTokens() {
        when(models.find(any(Bson.class))).thenReturn(List.of());
        var usage = new Usage(33_832, null, null, 5_000, 28_832, null, null, null, 28_832);

        var price = service.resolveVideo(new MediaPricingService.VideoPricingRequest(
                "gemini-omni-flash-preview", "gemini-omni-flash-preview", 10, null, null, 1.01, usage));

        assertEquals(1.01, price.costUsd());
        assertEquals("upstream", price.source());
    }

    @Test
    void resolveVideoReturnsUnavailableWithoutCreditsRate() {
        when(models.find(any(Bson.class))).thenReturn(List.of());

        var price = service.resolveVideo(videoRequest("no-such-video-model", "no-such-video-model", 8, 252.0, null, null));

        assertNull(price.costUsd());
        assertEquals("unavailable", price.source());
    }

    @Test
    void gatewayModelLookupUsesModelIdFilter() {
        when(models.find(Filters.eq("model_id", "gpt-image-2"))).thenReturn(List.of(gatewayModel("gpt-image-2", 0.1, null)));

        var price = service.resolveImage("gpt-image-2", "gpt-image-2", null, 2);

        assertEquals(0.2, price.costUsd());
        assertEquals("gateway_model", price.source());
    }
}
