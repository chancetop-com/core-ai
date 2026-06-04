package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SessionConfig;
import ai.core.llm.LLMProviders;
import ai.core.persistence.PersistenceProviders;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.util.IdLists;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.SubAgentToolCall;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class SessionSubAgentManager {
    private final Logger logger = LoggerFactory.getLogger(SessionSubAgentManager.class);

    private final MongoCollection<AgentDefinition> agentDefinitionCollection;
    private final ChatMessageService chatMessageService;
    private final ToolRegistryService toolRegistryService;
    private final SystemPromptService systemPromptService;
    private final LLMProviders llmProviders;
    private final PersistenceProviders persistenceProviders;

    public SessionSubAgentManager(MongoCollection<AgentDefinition> agentDefinitionCollection,
                                   ChatMessageService chatMessageService,
                                   ToolRegistryService toolRegistryService,
                                   SystemPromptService systemPromptService,
                                   LLMProviders llmProviders,
                                   PersistenceProviders persistenceProviders) {
        this.agentDefinitionCollection = agentDefinitionCollection;
        this.chatMessageService = chatMessageService;
        this.toolRegistryService = toolRegistryService;
        this.systemPromptService = systemPromptService;
        this.llmProviders = llmProviders;
        this.persistenceProviders = persistenceProviders;
    }

    public List<String> loadSubAgents(InProcessAgentSession session, List<AgentDefinition> definitions) {
        var names = applySubAgentsToSession(session, definitions);
        var ids = definitions.stream().map(d -> d.id).toList();
        chatMessageService.addLoadedSubAgentIds(session.id(), ids);
        return names;
    }

    public List<String> loadSubAgentsFromDefinition(InProcessAgentSession session, AgentDefinition definition) {
        var subAgentIds = IdLists.clean(definition.subAgentIds);
        if (subAgentIds.isEmpty()) {
            return List.of();
        }
        var names = new ArrayList<String>();
        for (var subAgentId : subAgentIds) {
            try {
                var subAgentDef = agentDefinitionCollection.get(subAgentId)
                        .orElseThrow(() -> new RuntimeException("subagent not found, id=" + subAgentId));
                var subAgent = buildSubAgent(subAgentDef);
                var subAgentToolCall = SubAgentToolCall.builder().subAgent(subAgent).build();
                session.loadTools(List.of(subAgentToolCall));
                names.add(subAgentDef.name);
                logger.info("loaded subagent {} for session {}", subAgentDef.name, session.id());
            } catch (Exception e) {
                logger.warn("failed to load subagent {} for session {}: {}", subAgentId, session.id(), e.getMessage());
            }
        }
        return names;
    }

    List<String> applySubAgentsToSession(InProcessAgentSession session, List<AgentDefinition> definitions) {
        var names = new ArrayList<String>();
        for (var definition : definitions) {
            var subAgent = buildSubAgent(definition);
            var subAgentToolCall = SubAgentToolCall.builder().subAgent(subAgent).build();
            session.loadTools(List.of(subAgentToolCall));
            names.add(definition.name);
        }
        return names;
    }

    private Agent buildSubAgent(AgentDefinition definition) {
        var config = toSessionConfig(definition);
        var tools = resolveTools(definition);
        return buildAgent(config, tools.isEmpty() ? null : tools, null, definition.name);
    }

    public List<ToolCall> resolveTools(AgentDefinition definition) {
        if (definition.publishedConfig != null && definition.publishedConfig.tools != null && !definition.publishedConfig.tools.isEmpty()) {
            return toolRegistryService.resolveToolRefs(definition.publishedConfig.tools);
        } else if (definition.tools != null && !definition.tools.isEmpty()) {
            return toolRegistryService.resolveToolRefs(definition.tools);
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

    @SuppressWarnings("checkstyle:NestedIfDepth")
    public Agent buildAgent(SessionConfig config, List<ToolCall> tools, ExecutionContext context, String agentName) {
        return buildAgent(config, tools, context, agentName, null);
    }

    @SuppressWarnings("checkstyle:NestedIfDepth")
    public Agent buildAgent(SessionConfig config, List<ToolCall> tools, ExecutionContext context, String agentName, Map<String, Object> extraSystemVars) {
        var llmProvider = llmProviders.getProvider();
        var builder = Agent.builder()
                .name(agentName != null ? agentName.replaceAll("\\s+", "-") : "assistant")
                .llmProvider(llmProvider)
                .toolCalls(tools != null && !tools.isEmpty() ? tools : BuiltinTools.ALL)
                .temperature(config != null && config.temperature != null ? config.temperature : 0.8);
        if (config != null) {
            if (config.systemPrompt != null) {
                builder.systemPrompt(config.systemPrompt);
            } else {
                builder.systemPrompt("You are a helpful AI assistant.");
            }
            if (config.model != null) builder.model(config.model);
            if (config.multiModalModel != null) {
                builder.multiModalModel(config.multiModalModel);
            } else if (config.model == null) {
                var mmModel = llmProvider.config.getMultiModalModel();
                if (mmModel != null) builder.multiModalModel(mmModel);
            }
            if (config.maxTurns != null) builder.maxTurn(config.maxTurns);
        } else {
            builder.systemPrompt("You are a helpful AI assistant.");
            var mmModel = llmProvider.config.getMultiModalModel();
            if (mmModel != null) builder.multiModalModel(mmModel);
        }
        if (context != null) builder.executionContext(context);
        var provider = persistenceProviders.getDefaultPersistenceProvider();
        if (provider != null) builder.persistenceProvider(provider);
        if (extraSystemVars != null) {
            extraSystemVars.forEach(builder::extraSystemVariable);
        }
        return builder.build();
    }
}
