package ai.core.agent;

import ai.core.agent.doomloop.DoomLoopLifecycle;
import ai.core.agent.doomloop.DoomLoopStrategy;
import ai.core.agent.doomloop.RepetitiveCallStrategy;
import ai.core.agent.doomloop.TaskReminderStrategy;
import ai.core.agent.doomloop.TodoReminderStrategy;
import ai.core.context.ToolCallPruning;
import ai.core.context.ToolCallPruningLifecycle;
import ai.core.memory.MemoryConfig;
import ai.core.memory.MemoryLifecycle;
import ai.core.prompt.langfuse.LangfusePrompt;
import ai.core.prompt.langfuse.LangfusePromptProvider;
import ai.core.prompt.langfuse.LangfusePromptProviderRegistry;
import ai.core.rag.RagConfig;
import ai.core.reflection.ReflectionConfig;
import ai.core.termination.terminations.MaxRoundTermination;
import ai.core.termination.terminations.StopMessageTermination;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolExposure;
import ai.core.tool.tools.ToolActivationTool;
import ai.core.tool.tools.WriteTodoTaskTool;
import ai.core.tool.tools.WriteTodosTool;

import java.util.ArrayList;

/**
 * @author stephen
 */
final class AgentAssembler {

    static void assemble(AgentBuilder builder, Agent agent) {
        agent.systemPrompt = builder.systemPrompt;
        agent.promptTemplate = builder.promptTemplate == null ? "" : builder.promptTemplate;
        agent.maxTurnNumber = builder.maxTurnNumber != null ? builder.maxTurnNumber : 100;
        agent.temperature = builder.temperature;
        agent.model = builder.model;
        agent.multiModalModel = builder.multiModalModel;
        agent.llmProvider = builder.llmProvider;
        if (agent.llmProvider == null) {
            throw new Error("llmProvider is required for agent, please set it with llmProvider() method");
        }
        agent.toolRegistry = builder.toolRegistry;
        agent.subAgents = builder.subAgents;
        agent.ragConfig = builder.ragConfig;
        agent.reflectionConfig = builder.reflectionConfig;
        agent.reflectionListener = builder.reflectionListener;
        agent.useGroupContext = builder.useGroupContext;
        agent.setPersistence(new AgentPersistence());
        agent.agentLifecycles = new ArrayList<>(builder.agentLifecycles);
        agent.compression = builder.compression;
        agent.reasoningEffort = builder.reasoningEffort;
        if (builder.reflectionConfig == null && Boolean.TRUE.equals(builder.enableReflection)) {
            agent.reflectionConfig = ReflectionConfig.defaultReflectionConfig();
        }
        if (agent.reflectionConfig != null) {
            agent.setMaxRound(agent.reflectionConfig.maxRound());
            agent.addTermination(new MaxRoundTermination());
            agent.addTermination(new StopMessageTermination());
        }
        if (agent.ragConfig == null) {
            agent.ragConfig = new RagConfig();
        }
        agent.getSystemVariables().putAll(builder.extraSystemVariables);
    }

    static void configureMemory(AgentBuilder builder) {
        if (builder.memory == null) return;
        var config = builder.memoryConfig != null ? builder.memoryConfig : MemoryConfig.defaultConfig();
        var lifecycle = new MemoryLifecycle(builder.memory, config.getMaxRecallRecords());
        builder.agentLifecycles.add(lifecycle);
        if (config.isAutoRecall()) {
            builder.toolCalls.add(lifecycle.getMemoryRecallTool());
        }
    }

    static void fetchLangfusePromptsIfConfigured(AgentBuilder builder) {
        if (builder.langfuseSystemPromptName == null && builder.langfusePromptTemplateName == null) return;
        var provider = LangfusePromptProviderRegistry.getProvider();
        if (provider == null) {
            throw new IllegalStateException("Langfuse prompts are configured but Langfuse provider is not initialized. "
                    + "Please configure langfuse.prompt.base.url in your properties file.");
        }
        try {
            if (builder.langfuseSystemPromptName != null) {
                builder.systemPrompt = fetchLangfusePrompt(provider, builder.langfuseSystemPromptName, builder.langfusePromptVersion, builder.langfusePromptLabel).getPromptContent();
            }
            if (builder.langfusePromptTemplateName != null) {
                builder.promptTemplate = fetchLangfusePrompt(provider, builder.langfusePromptTemplateName, builder.langfusePromptVersion, builder.langfusePromptLabel).getPromptContent();
            }
        } catch (LangfusePromptProvider.LangfusePromptException e) {
            throw new RuntimeException("Failed to fetch prompts from Langfuse", e);
        }
    }

    private static LangfusePrompt fetchLangfusePrompt(LangfusePromptProvider provider, String name, Integer version, String label) throws LangfusePromptProvider.LangfusePromptException {
        if (version != null) return provider.getPrompt(name, version);
        if (label != null) return provider.getPromptByLabel(name, label);
        return provider.getPrompt(name);
    }

    static void configureDoomLoop(AgentBuilder builder) {
        var strategies = new ArrayList<DoomLoopStrategy>();
        if (builder.doomLoopEnabled) {
            strategies.add(new RepetitiveCallStrategy(builder.doomLoopWindowSize, builder.doomLoopThreshold));
        }
        boolean hasTaskV2 = hasTool(builder, WriteTodoTaskTool.TOOL_NAME_CREATE);
        if (hasTaskV2) {
            strategies.add(new TaskReminderStrategy());
        }
        boolean hasWriteTodos = hasTool(builder, WriteTodosTool.WT_TOOL_NAME);
        if (hasWriteTodos) {
            strategies.add(new TodoReminderStrategy());
        }
        if (!strategies.isEmpty()) {
            builder.agentLifecycles.add(new DoomLoopLifecycle(strategies));
        }
    }

    private static boolean hasTool(AgentBuilder builder, String toolName) {
        if (builder.toolRegistry != null) {
            return builder.toolRegistry.getToolCalls().stream().anyMatch(t -> toolName.equals(t.getName()));
        }
        return builder.toolCalls.stream().anyMatch(t -> toolName.equals(t.getName()));
    }

    static void configureToolCallPruning(AgentBuilder builder) {
        if (builder.toolCallPruningEnabled) {
            var pruningCfg = builder.toolCallPruningConfig != null ? builder.toolCallPruningConfig : ToolCallPruning.Config.defaultConfig();
            builder.agentLifecycles.addFirst(new ToolCallPruningLifecycle(new ToolCallPruning(pruningCfg.keepRecentSegments(), pruningCfg.excludeToolNames())));
        }
    }

    static void configureToolDiscovery(AgentBuilder builder) {
        var discoverableTools = builder.toolCalls.stream().filter(ToolCall::isDiscoverable).toList();
        if (!discoverableTools.isEmpty()) {
            for (var tool : discoverableTools) {
                tool.setLlmVisible(Boolean.FALSE);
                tool.setExposure(ToolExposure.DEFERRED);
            }
            builder.toolCalls.add(ToolActivationTool.builder().allToolCalls(builder.toolCalls).build());
        }
    }

    private AgentAssembler() {
    }
}
