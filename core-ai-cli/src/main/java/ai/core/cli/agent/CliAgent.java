package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.llm.LLMProviders;
import ai.core.persistence.PersistenceProvider;
import ai.core.tool.BuiltinTools;

/**
 * @author stephen
 */
public class CliAgent {
    public static Agent of(LLMProviders providers, String modelOverride, int maxTurn,
                           PersistenceProvider persistenceProvider) {

        var builder = Agent.builder()
                .llmProvider(providers.getProvider())
                .systemPrompt("You are a helpful AI coding assistant.")
                .maxTurn(maxTurn)
                .toolCalls(BuiltinTools.ALL)
                .temperature(0.8);

        if (persistenceProvider != null) {
            builder.persistenceProvider(persistenceProvider);
        }
        if (modelOverride != null) {
            builder.model(modelOverride);
        }

        return builder.build();
    }
}
