package ai.core.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertEquals(2.5e-06, info.inputCostPerToken());
        assertEquals(1e-05, info.outputCostPerToken());
        assertEquals(1.25e-06, info.cacheReadInputTokenCost());
    }

    @Test
    void testEstimateCostUsdWithCachedTokens() {
        var cost = registry.estimateCostUsd("gpt-4o", 1_000, 200, 400);

        assertNotNull(cost);
        assertEquals(0.004, cost, 0.0000001);
    }
}
