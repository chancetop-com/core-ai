package ai.core.agent;

import ai.core.llm.providers.MockLLMProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Xander
 */
class AgentPreferCaptionPathTest {
    @Test
    void preferCaptionPathForcesCaptionRouting() {
        var agent = Agent.builder()
                .name("caption-preferred")
                .description("test agent")
                .systemPrompt("test")
                .llmProvider(new MockLLMProvider())
                .preferCaptionPath(true)
                .build();

        assertFalse(agent.getExecutionContext().isVisionNative());
    }

    @Test
    void unknownModelDefaultsToVisionNative() {
        var agent = Agent.builder()
                .name("default-routing")
                .description("test agent")
                .systemPrompt("test")
                .llmProvider(new MockLLMProvider())
                .build();

        assertTrue(agent.getExecutionContext().isVisionNative());
    }
}
