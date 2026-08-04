package ai.core.agent;

import ai.core.llm.InputModality;
import ai.core.llm.ModalitySupport;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.tool.tools.CaptionImageTool;
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

    @Test
    void textOnlyMainModelAutomaticallyExposesCaptionImageTool() {
        var provider = new MockLLMProvider();
        provider.setModalityRegistry((model, modality) -> {
            if (modality == InputModality.TEXT) return ModalitySupport.SUPPORTED;
            return "deepseek-model".equals(model) ? ModalitySupport.UNSUPPORTED : ModalitySupport.SUPPORTED;
        });
        var agent = Agent.builder()
                .name("caption-fallback")
                .description("test agent")
                .systemPrompt("test")
                .llmProvider(provider)
                .model("deepseek-model")
                .multiModalModel("vision-model")
                .build();

        assertFalse(agent.getExecutionContext().isVisionNative());
        assertTrue(agent.getToolCalls().stream().anyMatch(tool -> CaptionImageTool.TOOL_NAME.equals(tool.getName())));
    }

    @Test
    void visionMainKeepsCaptionToolInDispatchRegistryForRuntimeDowngrade() {
        var provider = new MockLLMProvider();
        provider.setModalityRegistry((model, modality) -> ModalitySupport.SUPPORTED);
        var agent = Agent.builder()
                .name("vision-main")
                .description("test agent")
                .systemPrompt("test")
                .llmProvider(provider)
                .model("vision-model")
                .multiModalModel("vision-fallback")
                .build();

        assertTrue(agent.getExecutionContext().isVisionNative());
        assertTrue(agent.getToolCalls().stream().anyMatch(tool -> CaptionImageTool.TOOL_NAME.equals(tool.getName())));
    }
}
