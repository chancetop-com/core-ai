package ai.core.server.agent;

import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;
import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.api.server.session.SessionConfig;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviders;
import ai.core.persistence.PersistenceProviders;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.skill.SkillToolAssembler;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.util.IdLists;
import ai.core.prompt.PromptInject;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.registry.ToolRegistryFactory;
import ai.core.tool.tools.SubAgentToolCall;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds sub-agents (tools + skills included) from agent definitions.
 * Shared by the run path (AgentRunner) and the session path (SessionSubAgentManager) so both assemble
 * sub-agents identically — in particular both wire skills, which previously only the top-level agent did.
 *
 * @author Xander
 */
public class SubAgentAssembler {
    private final Logger logger = LoggerFactory.getLogger(SubAgentAssembler.class);

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;
    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    SystemPromptService systemPromptService;
    @Inject
    LLMProviders llmProviders;
    @Inject
    PersistenceProviders persistenceProviders;
    @Inject
    SkillToolAssembler skillToolAssembler;

    /**
     * Loads sub-agent definitions by id and wraps each as a callable tool.
     * A missing or broken sub-agent is logged and skipped so it never blocks the parent agent from starting.
     */
    public List<SubAgentToolCall> assemble(List<String> subAgentIds, String sessionId) {
        var ids = IdLists.clean(subAgentIds);
        if (ids.isEmpty()) return List.of();
        var tools = new ArrayList<SubAgentToolCall>();
        for (var id : ids) {
            try {
                var definition = agentDefinitionCollection.get(id)
                        .orElseThrow(() -> new RuntimeException("subagent not found, id=" + id));
                var subAgent = buildSubAgent(definition, sessionId);
                tools.add(SubAgentToolCall.builder().subAgent(subAgent).build());
                logger.info("assembled subagent {} (id={})", definition.name, id);
            } catch (Exception e) {
                logger.warn("failed to assemble subagent id={}: {}", id, e.getMessage());
            }
        }
        return tools;
    }

    public Agent buildSubAgent(AgentDefinition definition, String sessionId) {
        var config = toSessionConfig(definition);
        var toolRegistry = resolveToolsToRegistry(definition, sessionId);
        skillToolAssembler.attach(resolveSkillIds(definition), toolRegistry);
        var bc = new BuildAgentConfig(config, toolRegistry, null, definition.name, null, definition.id, null, null, null);
        return buildAgent(bc);
    }

    private List<String> resolveSkillIds(AgentDefinition definition) {
        var source = definition.publishedConfig;
        return source != null && source.skillIds != null ? source.skillIds : definition.skillIds;
    }

    public ToolRegistry resolveToolsToRegistry(AgentDefinition definition, String sessionId) {
        if (definition.publishedConfig != null && definition.publishedConfig.tools != null && !definition.publishedConfig.tools.isEmpty()) {
            return toolRegistryService.resolveToToolRegistry(definition.publishedConfig.tools, sessionId);
        } else if (definition.tools != null && !definition.tools.isEmpty()) {
            return toolRegistryService.resolveToToolRegistry(definition.tools, sessionId);
        }
        return ToolRegistryFactory.createEmpty();
    }

    public List<ToolCall> resolveTools(AgentDefinition definition, String sessionId) {
        if (definition.publishedConfig != null && definition.publishedConfig.tools != null && !definition.publishedConfig.tools.isEmpty()) {
            return toolRegistryService.resolveToolRefs(definition.publishedConfig.tools, sessionId);
        } else if (definition.tools != null && !definition.tools.isEmpty()) {
            return toolRegistryService.resolveToolRefs(definition.tools, sessionId);
        }
        return List.of();
    }

    public SessionConfig toSessionConfig(AgentDefinition definition) {
        var config = new SessionConfig();
        var source = definition.publishedConfig;
        var hasSource = source != null;
        var systemPromptId = hasSource && source.systemPromptId != null ? source.systemPromptId : definition.systemPromptId;
        var inlineSystemPrompt = hasSource && source.systemPrompt != null ? source.systemPrompt : definition.systemPrompt;
        config.systemPrompt = systemPromptId != null ? systemPromptService.resolveContent(systemPromptId) : inlineSystemPrompt;
        config.model = hasSource && source.model != null ? source.model : definition.model;
        config.multiModalModel = hasSource && source.multiModalModel != null ? source.multiModalModel : definition.multiModalModel;
        config.temperature = hasSource && source.temperature != null ? source.temperature : definition.temperature;
        config.maxTurns = hasSource && source.maxTurns != null ? source.maxTurns : definition.maxTurns;
        return config;
    }

    public Agent buildAgent(BuildAgentConfig c) {
        var llmProvider = llmProviders.getProvider();
        var builder = Agent.builder()
                .name(c.agentName != null && !c.agentName.isBlank() ? c.agentName.trim().replaceAll("[\\s<|\\\\/>]+", "-") : "assistant")
                .llmProvider(llmProvider)
                .toolRegistry(c.toolRegistry)
                .temperature(c.config != null && c.config.temperature != null ? c.config.temperature : 0.8);
        if (c.agentId != null && !c.agentId.isBlank()) {
            builder.id(c.agentId);
        }
        if (c.config != null) {
            if (c.config.systemPrompt != null) {
                builder.systemPrompt(c.config.systemPrompt);
            } else {
                builder.systemPrompt("You are a helpful AI assistant.");
            }
            if (c.config.model != null) builder.model(c.config.model);
            configureMultiModalModel(builder, c.config, llmProvider);
            if (c.config.maxTurns != null) builder.maxTurn(c.config.maxTurns);
        } else {
            builder.systemPrompt("You are a helpful AI assistant.");
            var mmModel = llmProvider.config.getMultiModalModel();
            if (mmModel != null) builder.multiModalModel(mmModel);
        }
        if (c.context != null) builder.executionContext(c.context);
        var provider = persistenceProviders.getDefaultPersistenceProvider();
        if (provider != null) builder.persistenceProvider(provider);
        if (c.extraSystemVars != null) {
            c.extraSystemVars.forEach(builder::extraSystemVariable);
        }
        if (c.extraLifecycles != null && !c.extraLifecycles.isEmpty()) {
            builder.agentLifecycle(c.extraLifecycles);
        }
        if (c.memoryInject != null) {
            builder.systemPromptSection(c.memoryInject);
        }
        if (c.channelInject != null) {
            builder.systemPromptSection(c.channelInject);
        }
        return builder.build();
    }

    private void configureMultiModalModel(AgentBuilder builder, SessionConfig config, LLMProvider llmProvider) {
        if (config.multiModalModel != null) {
            builder.multiModalModel(config.multiModalModel);
            return;
        }
        if (config.model != null) return;
        var mmModel = llmProvider.config.getMultiModalModel();
        if (mmModel != null) builder.multiModalModel(mmModel);
    }

    public record BuildAgentConfig(SessionConfig config, ToolRegistry toolRegistry, ExecutionContext context,
                                    String agentName, Map<String, Object> extraSystemVars, String agentId,
                                    List<AbstractLifecycle> extraLifecycles, PromptInject memoryInject,
                                    PromptInject channelInject) {
    }
}
