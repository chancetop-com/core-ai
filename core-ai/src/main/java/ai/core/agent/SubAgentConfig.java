package ai.core.agent;

import ai.core.llm.LLMProvider;

/**
 * Configuration for a sub-agent. Pure value object — no disk/config source knowledge.
 *
 * @author lim chen
 */
public class SubAgentConfig {
    private String model;
    private LLMProvider llmProvider;

    public String model() {
        return model;
    }

    public SubAgentConfig model(String model) {
        this.model = model;
        return this;
    }

    public LLMProvider llmProvider() {
        return llmProvider;
    }

    public SubAgentConfig llmProvider(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
        return this;
    }
}
