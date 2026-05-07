package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.prompt.PromptInject;
import ai.core.tool.BuiltinTools;

import java.util.List;

/**
 * author: lim chen
 * date: 2026/5/7
 * description:
 */
public class DefaultGeneralAgent {
    public static final String AGENT_NAME = "general-purpose-agent";
    public static final String AGENT_DESCRIPTION = """
            General-purpose agent for researching complex questions and executing multi-step tasks. Use this agent to execute multiple units of work in parallel.
            """;

    public static Agent of(LLMProvider llmProvider, String model, StreamingCallback streamingCallback, List<AbstractLifecycle> lifecycles, List<PromptInject> promptInjects) {
        return Agent.builder()
                .name(AGENT_NAME)
                .streamingCallback(streamingCallback)
                .model(model)
                .agentLifecycle(lifecycles)
                .description(AGENT_DESCRIPTION)
                .systemPromptSections(resolvePromptInjects(promptInjects))
                .toolCalls(BuiltinTools.combine(BuiltinTools.FILE_OPERATIONS, BuiltinTools.CODE_EXECUTION, BuiltinTools.WEB))
                .llmProvider(llmProvider).build();
    }

    private static List<PromptInject> resolvePromptInjects(List<PromptInject> promptInjects) {
        return promptInjects.stream().filter(promptInject -> List.of(
                PromptInject.SectionType.IDENTITY,
                PromptInject.SectionType.ENVIRONMENT,
                PromptInject.SectionType.INSTRUCTIONS,
                PromptInject.SectionType.MEMORY).contains(promptInject.type())).toList();
    }
}
