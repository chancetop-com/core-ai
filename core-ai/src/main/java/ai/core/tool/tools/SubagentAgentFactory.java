package ai.core.tool.tools;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.profile.AgentProfile;
import ai.core.agent.profile.AgentProfileRegistry;
import ai.core.defaultagents.DefaultCodeSimplifierAgent;
import ai.core.defaultagents.DefaultExploreAgent;
import ai.core.defaultagents.DefaultGeneralAgent;
import ai.core.defaultagents.DeepResearchAgent;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.prompt.PromptInject;
import ai.core.tool.registry.ToolRegistry;

import java.util.ArrayList;

/**
 * Builds the concrete subagent for a run. Kept apart from the task tool so the tool only owns
 * dispatch, gating and thread lifecycle.
 *
 * @author stephen
 */
final class SubagentAgentFactory {
    static Agent create(String subagentType, ExecutionContext context, AgentProfile profile,
                        SubagentModelSelector.ModelSelection selection) {
        var llmProvider = selection.provider() != null ? selection.provider() : resolveLlmProvider(subagentType, context);
        var model = selection.model();
        var maxTurnNumber = resolveMaxTurnNumber(subagentType, context);
        var toolRegistry = context.getToolRegistry();
        AgentProfileRegistry profileRegistry = context.getAgentProfileRegistry();
        if (profileRegistry != null) {
            if (profile == null) {
                throw new RuntimeException("Unknown subagent type: " + subagentType);
            }
            return buildFromProfile(profile, llmProvider, model, maxTurnNumber, toolRegistry, context);
        }
        return createBuiltin(subagentType, llmProvider, model, maxTurnNumber, toolRegistry, context);
    }

    private static Agent createBuiltin(String subagentType, LLMProvider llmProvider, String model, int maxTurnNumber,
                                       ToolRegistry toolRegistry, ExecutionContext context) {
        if (DeepResearchAgent.AGENT_NAME.equals(subagentType)) {
            return DeepResearchAgent.of(toolRegistry, llmProvider, model, context, maxTurnNumber);
        }
        if (DefaultExploreAgent.AGENT_NAME.equals(subagentType)) {
            return DefaultExploreAgent.of(toolRegistry, llmProvider, model, context, maxTurnNumber);
        }
        if (DefaultCodeSimplifierAgent.AGENT_NAME.equals(subagentType)) {
            return DefaultCodeSimplifierAgent.of(toolRegistry, llmProvider, model, context, maxTurnNumber);
        }
        if (DefaultGeneralAgent.AGENT_NAME.equals(subagentType)) {
            return DefaultGeneralAgent.of(toolRegistry, llmProvider, model, context, maxTurnNumber);
        }
        throw new RuntimeException("Unknown subagent type: " + subagentType);
    }

    private static Agent buildFromProfile(AgentProfile profile, LLMProvider llmProvider, String model, int maxTurnNumber,
                                          ToolRegistry toolRegistry, ExecutionContext context) {
        var builder = Agent.builder()
                .name(profile.name())
                .description(profile.description())
                .systemPrompt(profile.systemPrompt() != null ? profile.systemPrompt() : "")
                .llmProvider(llmProvider)
                .model(model)
                .maxTurn(profile.maxTurnNumber() != null ? profile.maxTurnNumber() : maxTurnNumber)
                .streamingCallback(context.getStreamingCallback())
                .agentLifecycle(context.getLifecycle())
                .toolRegistry(toolRegistry);
        if (profile.tools() != null && !profile.tools().isEmpty()) {
            builder.toolNames(new ArrayList<>(profile.tools()));
        }
        builder.systemPromptSections(context.getPromptSections().stream()
                .filter(s -> s.type() == PromptInject.SectionType.ENVIRONMENT
                        || s.type() == PromptInject.SectionType.INSTRUCTIONS
                        || s.type() == PromptInject.SectionType.MEMORY)
                .toList());
        if (profile.reasoningEffort() != null) {
            var effort = ReasoningEffort.fromString(profile.reasoningEffort());
            if (effort != null) builder.reasoningEffort(effort);
        }
        if (profile.temperature() != null) {
            builder.temperature(profile.temperature());
        }
        return builder.build();
    }

    private static int resolveMaxTurnNumber(String subagentType, ExecutionContext context) {
        var configs = context.getSubAgentConfigs();
        if (configs != null) {
            var config = configs.get(subagentType);
            if (config != null && config.maxTurnNumber() != null) {
                return config.maxTurnNumber();
            }
        }
        return 200;
    }

    private static LLMProvider resolveLlmProvider(String subagentType, ExecutionContext context) {
        var configs = context.getSubAgentConfigs();
        if (configs != null) {
            var config = configs.get(subagentType);
            if (config != null && config.llmProvider() != null) {
                return config.llmProvider();
            }
        }
        return context.getLlmProvider();
    }

    private SubagentAgentFactory() {
    }
}
