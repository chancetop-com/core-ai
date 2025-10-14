package ai.core.prompt.langfuse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for LangfusePromptProvider
 * Note: These tests require a running Langfuse instance with test prompts configured
 *
 * @author stephen
 */
class LangfusePromptProviderTest {

    private LangfusePromptConfig config;
    private LangfusePromptProvider provider;

    @BeforeEach
    void setUp() {
        // Configure for local/test Langfuse instance
        // In production, these values would come from configuration
        config = LangfusePromptConfig.builder()
            .baseUrl("https://cloud.langfuse.com")  // Replace with your Langfuse URL
            .publicKey("pk-lf-test")  // Replace with test public key
            .secretKey("sk-lf-test")  // Replace with test secret key
            .timeoutSeconds(10)
            .build();

        provider = new LangfusePromptProvider(config, true);
    }

    @Test
    void testConfigBuilder() {
        // Test configuration building
        assertNotNull(config);
        assertEquals("https://cloud.langfuse.com", config.getBaseUrl());
        assertEquals("pk-lf-test", config.getPublicKey());
        assertEquals("sk-lf-test", config.getSecretKey());
        assertEquals(10, config.getTimeoutSeconds());
        assertEquals("https://cloud.langfuse.com/api/public/v2/prompts", config.getPromptEndpoint());
    }

    @Test
    void testConfigWithHeaders() {
        Map<String, String> customHeaders = new HashMap<>();
        customHeaders.put("X-Custom-Header", "test-value");

        LangfusePromptConfig configWithHeaders = LangfusePromptConfig.builder()
            .baseUrl("https://cloud.langfuse.com")
            .credentials("pk-test", "sk-test")
            .addHeaders(customHeaders)
            .build();

        assertNotNull(configWithHeaders.getHeaders());
        assertTrue(configWithHeaders.getHeaders().containsKey("X-Custom-Header"));
        assertTrue(configWithHeaders.getHeaders().containsKey("Authorization"));
    }

    @Test
    void testPromptModel() {
        // Test LangfusePrompt model
        LangfusePrompt prompt = new LangfusePrompt();
        prompt.setName("test-prompt");
        prompt.setVersion(1);
        prompt.setType("text");
        prompt.setPrompt("Hello {{name}}!");

        assertEquals("test-prompt", prompt.getName());
        assertEquals(1, prompt.getVersion());
        assertEquals("text", prompt.getType());
        assertTrue(prompt.isTextPrompt());
        assertFalse(prompt.isChatPrompt());
        assertEquals("Hello {{name}}!", prompt.getPromptContent());
    }

    @Test
    void testChatPromptModel() {
        // Test chat prompt
        LangfusePrompt prompt = new LangfusePrompt();
        prompt.setName("chat-prompt");
        prompt.setType("chat");

        var chatMessages = java.util.List.of(
            new LangfusePrompt.ChatMessage("system", "You are a helpful assistant"),
            new LangfusePrompt.ChatMessage("user", "Hello")
        );
        prompt.setChatPrompt(chatMessages);

        assertTrue(prompt.isChatPrompt());
        assertFalse(prompt.isTextPrompt());
        assertNotNull(prompt.getChatPrompt());
        assertEquals(2, prompt.getChatPrompt().size());
    }

    @Test
    void testProviderCacheManagement() {
        // Test cache clearing
        assertDoesNotThrow(provider::clearCache);

        // Test removing specific cached prompt
        assertDoesNotThrow(() -> provider.removeCachedPrompt("test", null, null));
        assertDoesNotThrow(() -> provider.removeCachedPrompt("test", 1, null));
        assertDoesNotThrow(() -> provider.removeCachedPrompt("test", null, "production"));
    }

    @Test
    void testInvalidPromptName() {
        // Test with null prompt name
        assertThrows(IllegalArgumentException.class, () -> {
            provider.getPrompt(null);
        });

        // Test with empty prompt name
        assertThrows(IllegalArgumentException.class, () -> {
            provider.getPrompt("");
        });
    }

    @Test
    void testPromptTemplateIntegration() {
        // Create a mock prompt for testing
        LangfusePrompt mockPrompt = new LangfusePrompt();
        mockPrompt.setName("greeting");
        mockPrompt.setPrompt("Hello {{name}}, welcome to {{place}}!");
        mockPrompt.setType("text");

        // Test prompt content retrieval
        String content = mockPrompt.getPromptContent();
        assertNotNull(content);
        assertTrue(content.contains("{{name}}"));
        assertTrue(content.contains("{{place}}"));
    }

    @Test
    void testConfigNormalization() {
        // Test base URL normalization (removes trailing slashes)
        LangfusePromptConfig config1 = LangfusePromptConfig.builder()
            .baseUrl("https://cloud.langfuse.com/")
            .build();
        assertEquals("https://cloud.langfuse.com", config1.getBaseUrl());

        LangfusePromptConfig config2 = LangfusePromptConfig.builder()
            .baseUrl("https://cloud.langfuse.com///")
            .build();
        assertEquals("https://cloud.langfuse.com", config2.getBaseUrl());
    }

    @Test
    void testBasicAuthHeader() {
        LangfusePromptConfig config = LangfusePromptConfig.builder()
            .baseUrl("https://test.com")
            .credentials("test-pk", "test-sk")
            .build();

        Map<String, String> headers = config.getHeaders();
        assertTrue(headers.containsKey("Authorization"));
        assertTrue(headers.get("Authorization").startsWith("Basic "));
    }

    @Test
    void testProviderWithoutCache() {
        // Test provider with caching disabled
        LangfusePromptProvider noCacheProvider = new LangfusePromptProvider(config, false);
        assertNotNull(noCacheProvider);
    }

    /**
     * Integration test - requires actual Langfuse instance
     * Disabled by default, enable when testing against a real Langfuse server
     */
    /*
    @Test
    void integrationTestFetchPrompt() throws LangfusePromptProvider.LangfusePromptException {
        // This test requires:
        // 1. A running Langfuse instance
        // 2. Valid API credentials
        // 3. A prompt named "test-prompt" in Langfuse

        LangfusePromptProvider realProvider = new LangfusePromptProvider(
            LangfusePromptConfig.builder()
                .baseUrl("https://cloud.langfuse.com")
                .credentials("your-public-key", "your-secret-key")
                .build()
        );

        LangfusePrompt prompt = realProvider.getPrompt("test-prompt");
        assertNotNull(prompt);
        assertNotNull(prompt.getName());
        assertNotNull(prompt.getVersion());
    }
    */
}
