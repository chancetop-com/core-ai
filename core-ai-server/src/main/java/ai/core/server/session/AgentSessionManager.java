package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SessionConfig;
import ai.core.llm.LLMProviders;
import ai.core.persistence.PersistenceProviders;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.tools.SkillTool;
import ai.core.tool.tools.SubAgentToolCall;
import ai.core.session.InMemoryToolPermissionStore;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import core.framework.inject.Inject;
import core.framework.web.exception.NotFoundException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author stephen
 */
public class AgentSessionManager {
    private final ConcurrentMap<String, InProcessAgentSession> sessions = new ConcurrentHashMap<>();

    @Inject
    LLMProviders llmProviders;

    @Inject
    PersistenceProviders persistenceProviders;

    @Inject
    ToolRegistryService toolRegistryService;

    @Inject
    MongoSkillProvider mongoSkillProvider;

    @Inject
    SkillService skillService;

    public String createSession(SessionConfig config, String userId) {
        var sessionId = UUID.randomUUID().toString();
        var context = ExecutionContext.builder().sessionId(sessionId).userId(userId).build();
        var agent = buildAgent(config, null, context);
        var autoApproveAll = config != null && Boolean.TRUE.equals(config.autoApproveAll);
        var permissionStore = new InMemoryToolPermissionStore();
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore);
        sessions.put(sessionId, session);
        return sessionId;
    }

    public String createSessionFromAgent(AgentDefinition definition, SessionConfig overrides, String userId) {
        var config = toSessionConfig(definition);
        if (overrides != null) {
            if (overrides.model != null) config.model = overrides.model;
            if (overrides.temperature != null) config.temperature = overrides.temperature;
            if (overrides.systemPrompt != null) config.systemPrompt = overrides.systemPrompt;
            if (overrides.maxTurns != null) config.maxTurns = overrides.maxTurns;
            if (overrides.autoApproveAll != null) config.autoApproveAll = overrides.autoApproveAll;
        }
        var toolIds = definition.publishedConfig != null ? definition.publishedConfig.toolIds : definition.toolIds;
        var tools = toolRegistryService.resolveTools(toolIds);

        var sessionId = UUID.randomUUID().toString();
        var context = ExecutionContext.builder().sessionId(sessionId).userId(userId).build();
        var agent = buildAgent(config, tools.isEmpty() ? null : tools, context);
        var autoApproveAll = Boolean.TRUE.equals(config.autoApproveAll);
        var permissionStore = new InMemoryToolPermissionStore();
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore);
        sessions.put(sessionId, session);
        return sessionId;
    }

    public InProcessAgentSession getSession(String sessionId) {
        var session = sessions.get(sessionId);
        if (session == null) throw new NotFoundException("session not found, sessionId=" + sessionId);
        return session;
    }

    public void closeSession(String sessionId) {
        var session = sessions.remove(sessionId);
        if (session != null) session.close();
    }

    public List<String> loadTools(String sessionId, List<String> toolIds) {
        var session = getSession(sessionId);
        var tools = toolRegistryService.resolveTools(toolIds);
        if (tools.isEmpty()) {
            throw new NotFoundException("no tools found for ids: " + toolIds);
        }
        session.loadTools(tools);
        return tools.stream().map(ToolCall::getName).toList();
    }

    public List<String> loadSkills(String sessionId, List<String> skillIds) {
        var session = getSession(sessionId);
        var skills = skillService.resolveSkills(skillIds);
        if (skills.isEmpty()) {
            throw new NotFoundException("no skills found for ids: " + skillIds);
        }
        var registry = new SkillRegistry();
        registry.addProvider(mongoSkillProvider);
        var skillTool = SkillTool.builder().registry(registry).build();
        session.loadTools(List.of(skillTool));
        return skills.stream().map(SkillMetadata::getQualifiedName).toList();
    }

    public List<String> loadSubAgents(String sessionId, List<AgentDefinition> definitions) {
        var session = getSession(sessionId);
        var names = new java.util.ArrayList<String>();
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
        var toolIds = definition.publishedConfig != null ? definition.publishedConfig.toolIds : definition.toolIds;
        var tools = toolRegistryService.resolveTools(toolIds);

        var builder = Agent.builder()
                .name(definition.name.replaceAll("\\s+", "-"))
                .description(definition.description != null ? definition.description : definition.name)
                .llmProvider(llmProviders.getProvider())
                .toolCalls(tools.isEmpty() ? BuiltinTools.ALL : tools)
                .temperature(config.temperature != null ? config.temperature : 0.8);

        if (config.systemPrompt != null) {
            builder.systemPrompt(config.systemPrompt);
        }
        if (config.model != null) {
            builder.model(config.model);
        }
        if (config.maxTurns != null) {
            builder.maxTurn(config.maxTurns);
        }

        var skillRegistry = new SkillRegistry();
        skillRegistry.addProvider(mongoSkillProvider);
        builder.skillRegistry(skillRegistry);

        return builder.build();
    }

    private SessionConfig toSessionConfig(AgentDefinition definition) {
        var config = new SessionConfig();
        var source = definition.publishedConfig != null ? definition.publishedConfig : null;
        config.systemPrompt = source != null && source.systemPrompt != null ? source.systemPrompt : definition.systemPrompt;
        config.model = source != null && source.model != null ? source.model : definition.model;
        config.temperature = source != null && source.temperature != null ? source.temperature : definition.temperature;
        config.maxTurns = source != null && source.maxTurns != null ? source.maxTurns : definition.maxTurns;
        return config;
    }

    private Agent buildAgent(SessionConfig config, List<ToolCall> tools, ExecutionContext context) {
        var builder = Agent.builder()
                .llmProvider(llmProviders.getProvider())
                .toolCalls(tools != null ? tools : BuiltinTools.ALL)
                .temperature(config != null && config.temperature != null ? config.temperature : 0.8);

        if (config != null && config.systemPrompt != null) {
            builder.systemPrompt(config.systemPrompt);
        } else {
            builder.systemPrompt("You are a helpful AI assistant.");
        }
        if (config != null && config.model != null) {
            builder.model(config.model);
        }
        if (config != null && config.maxTurns != null) {
            builder.maxTurn(config.maxTurns);
        }

        if (context != null) {
            builder.executionContext(context);
        }

        var skillRegistry = new SkillRegistry();
        skillRegistry.addProvider(mongoSkillProvider);
        builder.skillRegistry(skillRegistry);

        var provider = persistenceProviders.getDefaultPersistenceProvider();
        if (provider != null) {
            builder.persistenceProvider(provider);
        }

        return builder.build();
    }
}
