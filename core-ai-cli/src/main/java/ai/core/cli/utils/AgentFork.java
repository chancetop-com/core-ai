package ai.core.cli.utils;

import ai.core.agent.Agent;
import ai.core.cli.log.SubAgentLogLifecycle;
import ai.core.persistence.PersistenceProvider;
import ai.core.tool.ToolCall;

import java.util.List;

/**
* author cyril
* description
* createTime  2026/5/11
**/
public final class AgentFork {

    public static Agent fork(Agent source, ForkConfig config) {
        var forked = forkConfigOnly(source, config);
        forked.restoreHistory(source.getMessages());
        return forked;
    }

    public static Agent forkConfigOnly(Agent source, ForkConfig config) {
        var tools = config.toolCalls != null ? config.toolCalls : source.getToolCalls();
        return Agent.builder()
                .llmProvider(source.getLLMProvider())
                .model(source.getModel())
                .systemPrompt(source.getSystemPrompt())
                .toolCalls(tools)
                .maxTurn(config.maxTurns)
                .temperature(config.temperature)
                .compression(config.compression)
                .persistenceProvider(config.persistenceProvider)
                .addAgentLifecycle(new SubAgentLogLifecycle(config.name))
                .build();
    }

    private AgentFork() {
    }


    public record ForkConfig(String name, int maxTurns, double temperature,
                              boolean compression, PersistenceProvider persistenceProvider,
                              List<ToolCall> toolCalls) {
        public ForkConfig(String name, int maxTurns, double temperature,
                           boolean compression, PersistenceProvider persistenceProvider) {
            this(name, maxTurns, temperature, compression, persistenceProvider, null);
        }
    }
}
