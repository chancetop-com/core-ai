package ai.core.defaultagents;

import ai.core.agent.VisionAgent;
import ai.core.llm.LLMProvider;

/**
 * @author stephen
 */
public class DefaultImageCaptionAgent {
    public VisionAgent of(LLMProvider llmProvider) {
        return VisionAgent.builder()
                .name("default-image-caption-agent")
                .description("Describe this image.")
                .prompt("Describe this image.")
                .llmProvider(llmProvider).build();
    }
}
