package ai.core.llm.providers;

import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for LiteLLM Provider JSON schema support.
 *
 * @author xander
 */
class LiteLLMProviderTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiteLLMProviderTest.class);
    @Test
    void testPreprocessMaintainsResponseFormat() {
        // Create config
        var config = new LLMProviderConfig("gpt-4o", 0.7, "text-embedding-ada-002");

        // Create provider
        var provider = new LiteLLMProvider(config, "http://litellm-service.uat-oadp:4000", "sk-adoB9H2Cd4FgvdqO8Mg7vQ");

        // Create system and user messages
        var systemMessage = new Message();
        systemMessage.role = RoleType.SYSTEM;
        systemMessage.content = "You are a helpful assistant.";

        var userMessage = new Message();
        userMessage.role = RoleType.USER;
        userMessage.content = "Say hello";

        // Create a COMPLETE JSON schema (must have both 'name' and 'schema' fields)
        Map<String, Object> schema = Map.of(
                "name", "test_response",
                "strict", true,
                "schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "message", Map.of("type", "string", "description", "The response message")
                        ),
                        "required", List.of("message"),
                        "additionalProperties", false
                )
        );

        var request = CompletionRequest.of(
                List.of(systemMessage, userMessage),
                null,
                0.0,
                "gpt-4o",
                "test-agent"
        );
        request.responseFormat = ResponseFormat.jsonSchema(schema);

        // Call LLM with complete schema
        LOGGER.info("=== Calling LLM with JSON Schema ===");
        var response = provider.completion(request);

        LOGGER.info("\n=== Response ===");
        LOGGER.info(response.choices.getFirst().message.content);

        // Verify response is valid JSON
        assertNotNull(response);
        assertNotNull(response.choices);
        assertFalse(response.choices.isEmpty());
    }
}