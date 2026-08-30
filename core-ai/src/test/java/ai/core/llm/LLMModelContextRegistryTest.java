package ai.core.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class LLMModelContextRegistryTest {
    private LLMModelContextRegistry registry;

    @BeforeEach
    void setUp() {
        // Ensure the registry is initialized before each test
        registry = LLMModelContextRegistry.getInstance();
    }

    @Test
    void testGetInstance() {
        assertNotNull(registry);
        assertTrue(registry.size() > 0, "Registry should have loaded models");
    }

    @Test
    void testGetMaxInputTokensGpt4o() {
        int maxTokens = registry.getMaxInputTokens("gpt-4o");
        assertEquals(128000, maxTokens);
    }

    @Test
    void testGetMaxOutputTokensGpt4o() {
        int maxOutputTokens = registry.getModelInfo("gpt-4o").maxOutputTokens();
        assertEquals(16384, maxOutputTokens);
    }

    @Test
    void testGetMaxInputTokensGpt4Turbo() {
        int maxTokens = registry.getMaxInputTokens("gpt-4-turbo");
        assertEquals(128000, maxTokens);
    }

    @Test
    void testGetMaxOutputTokensGpt4Turbo() {
        int maxOutputTokens = registry.getModelInfo("gpt-4o").maxOutputTokens();
        assertEquals(16384, maxOutputTokens);
    }

    @Test
    void testFuzzyMatchingWithDateSuffix() {
        // Should fall back to base model gpt-4o
        int maxTokens = registry.getMaxInputTokens("gpt-4o-2024-05-13");
        assertEquals(128000, maxTokens);
    }

    @Test
    void testUnknownModelReturnsDefault() {
        int maxTokens = registry.getMaxInputTokens("unknown-model-xyz");
        assertEquals(128000, maxTokens); // default value
    }

    @Test
    void testHasModel() {
        assertTrue(registry.hasModel("gpt-4o"));
        assertTrue(registry.hasModel("gpt-4-turbo"));
        assertFalse(registry.hasModel("unknown-model-xyz"));
    }

    @Test
    void testGetModelInfo() {
        var info = registry.getModelInfo("gpt-4o");
        assertNotNull(info);
        assertEquals(128000, info.maxInputTokens());
        assertEquals(16384, info.maxOutputTokens());
        assertEquals("openai", info.provider());
        assertEquals("chat", info.mode());
        assertEquals(2.5e-06, info.inputCostPerToken(), 1e-12);
        assertEquals(1e-05, info.outputCostPerToken(), 1e-12);
        assertEquals(1.25e-06, info.cacheReadInputTokenCost(), 1e-12);
    }

    @Test
    void testEstimateCostUsdWithCachedTokens() {
        var cost = registry.estimateCostUsd("gpt-4o", 1_000, 200, 400);

        assertNotNull(cost);
        assertEquals(0.004, cost, 0.0000001);
    }

    @Test
    void testEstimateCostUsdDeepSeekOffPeakUsesBasePrice() {
        // 2026-08-18T12:00:00Z = Beijing 20:00, off-peak
        var cost = registry.estimateCostUsd("deepseek-v4-pro", 1_000_000, 0, 0, Instant.parse("2026-08-18T12:00:00Z"));

        assertNotNull(cost);
        assertEquals(6.521739e-07 * 1_000_000, cost, 1e-9);
    }

    @Test
    void testEstimateCostUsdDeepSeekPeakAppliesMultiplier() {
        // 2026-08-18T02:00:00Z = Beijing 10:00, peak hour
        var cost = registry.estimateCostUsd("deepseek-v4-pro", 1_000_000, 0, 0, Instant.parse("2026-08-18T02:00:00Z"));

        assertNotNull(cost);
        assertEquals(6.521739e-07 * 1_000_000 * 2, cost, 1e-9);
    }

    @Test
    void testIsPeakHourBoundaries() {
        assertTrue(LLMModelContextRegistry.isPeakHour(Instant.parse("2026-08-18T01:00:00Z")));   // Beijing 09:00
        assertTrue(LLMModelContextRegistry.isPeakHour(Instant.parse("2026-08-18T03:59:59Z")));   // Beijing 11:59
        assertFalse(LLMModelContextRegistry.isPeakHour(Instant.parse("2026-08-18T04:00:00Z")));  // Beijing 12:00
        assertTrue(LLMModelContextRegistry.isPeakHour(Instant.parse("2026-08-18T06:00:00Z")));   // Beijing 14:00
        assertTrue(LLMModelContextRegistry.isPeakHour(Instant.parse("2026-08-18T09:59:59Z")));   // Beijing 17:59
        assertFalse(LLMModelContextRegistry.isPeakHour(Instant.parse("2026-08-18T10:00:00Z")));  // Beijing 18:00
        assertFalse(LLMModelContextRegistry.isPeakHour(Instant.parse("2026-08-18T12:00:00Z")));  // Beijing 20:00
    }

    @Test
    void testEstimateImageCostUsdGptImage2TokenDetails() {
        // azure/gpt-image-2: input_cost_per_token=5e-6, input_cost_per_image_token=8e-6, output_cost_per_image_token=3e-5
        var cost = registry.estimateImageCostUsd("gpt-image-2", 100, 50, 200, 1);

        assertNotNull(cost);
        assertEquals(100 * 5e-6 + 50 * 8e-6 + 200 * 3e-5, cost, 1e-12);
    }

    @Test
    void testEstimateImageCostGptImage2RecordsTokenUnits() {
        var estimate = registry.estimateImageCost("azure/gpt-image-2", 100, 50, 200, 1);

        assertNotNull(estimate);
        assertEquals("azure/gpt-image-2", estimate.pricingModelId());
        assertEquals(200.0, estimate.units());
        assertEquals("token", estimate.unitType());
    }

    @Test
    void testEstimateImageCostFallsBackToPerImage() {
        // aiml/dall-e-3 has no image-token prices but output_cost_per_image=0.052
        var cost = registry.estimateImageCostUsd("aiml/dall-e-3", null, null, null, 2);

        assertNotNull(cost);
        assertEquals(0.104, cost, 1e-12);
    }

    @Test
    void testEstimateImageCostUnknownModelReturnsNull() {
        assertNull(registry.estimateImageCostUsd("no-such-image-model", 100, 50, 200, 1));
        assertNull(registry.estimateImageCostUsd("gpt-image-2", null, null, null, 0));
    }

    @Test
    void testEstimateVideoCostVeoPreviewKey() {
        var cost = registry.estimateVideoCostUsd("gemini/veo-3.1-generate-preview", 8);

        assertNotNull(cost);
        assertEquals(3.2, cost, 1e-12);
    }

    @Test
    void testEstimateVideoCostVertexGaIdMapsToCatalogKey() {
        // Vertex GA id veo-3.1-generate-001 must resolve to the catalog key gemini/veo-3.1-generate-001 (0.4/s)
        var estimate = registry.estimateVideoCost("veo-3.1-generate-001", 8);

        assertNotNull(estimate);
        assertEquals("gemini/veo-3.1-generate-001", estimate.pricingModelId());
        assertEquals(3.2, estimate.costUsd(), 1e-12);
        assertEquals(8.0, estimate.units());
        assertEquals("second", estimate.unitType());
    }

    @Test
    void testEstimateVideoCostIgnoresChatModelPerSecondPrice() {
        // bedrock commitment chat models carry a compute output_cost_per_second that must not be used as media pricing
        assertNull(registry.estimateVideoCostUsd("bedrock/*/1-month-commitment/cohere.command-light-text-v14", 8));
    }

    @Test
    void testEstimateVideoCostUnknownModelOrMissingSecondsReturnsNull() {
        assertNull(registry.estimateVideoCostUsd("no-such-video-model", 8));
        assertNull(registry.estimateVideoCostUsd("gemini/veo-3.1-generate-preview", null));
    }
}
