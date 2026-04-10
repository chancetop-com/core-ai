package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SessionConfig;
import ai.core.llm.LLMProviders;
import ai.core.persistence.PersistenceProviders;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRef;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author stephen
 */
public class AgentSessionManager {
    private final Logger logger = LoggerFactory.getLogger(AgentSessionManager.class);
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
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
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
        }
        List<ToolCall> tools;
        if (definition.publishedConfig != null && definition.publishedConfig.tools != null && !definition.publishedConfig.tools.isEmpty()) {
            tools = toolRegistryService.resolveToolRefs(definition.publishedConfig.tools);
        } else if (definition.tools != null && !definition.tools.isEmpty()) {
            tools = toolRegistryService.resolveToolRefs(definition.tools);
        } else {
            tools = List.of();
        }

        var sessionId = UUID.randomUUID().toString();
        var context = ExecutionContext.builder().sessionId(sessionId).userId(userId).build();
        var agent = buildAgent(config, tools.isEmpty() ? null : tools, context);
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        sessions.put(sessionId, session);
        return sessionId;
    }

    public InProcessAgentSession getSession(String sessionId) {
        return getSession(sessionId, null);
    }

    public InProcessAgentSession getSession(String sessionId, SessionState state) {
        var session = sessions.get(sessionId);
        if (session != null) return session;

        // Try to rebuild from session state if available
        if (state != null) {
            logger.info("session not found locally, attempting to rebuild from state, sessionId={}", sessionId);
            session = rebuildSession(sessionId, state);
            if (session != null) {
                sessions.put(sessionId, session);
                logger.info("session rebuilt successfully, sessionId={}", sessionId);
                return session;
            }
        }

        throw new NotFoundException("session not found, sessionId=" + sessionId);
    }

    private InProcessAgentSession rebuildSession(String sessionId, SessionState state) {
        try {
            if (state.fromAgent && state.agentConfig != null) {
                return rebuildFromSnapshot(sessionId, state.agentConfig, state.userId);
            } else {
                return rebuildFromConfig(sessionId, state.config, state.userId);
            }
        } catch (Exception e) {
            logger.warn("failed to rebuild session, sessionId={}, error={}", sessionId, e.getMessage());
            return null;
        }
    }

    private InProcessAgentSession rebuildFromSnapshot(String sessionId, SessionState.AgentConfigSnapshot snapshot, String userId) {
        var config = new SessionConfig();
        config.systemPrompt = snapshot.systemPrompt;
        config.model = snapshot.model;
        config.temperature = snapshot.temperature;
        config.maxTurns = snapshot.maxTurns;

        List<ToolCall> tools = (snapshot.tools != null && !snapshot.tools.isEmpty())
                ? toolRegistryService.resolveToolRefs(snapshot.tools)
                : List.of();
        var context = userId != null ? ExecutionContext.builder().userId(userId).build() : null;
        var agent = buildAgent(config, tools.isEmpty() ? null : tools, context);
        return new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
    }

    private InProcessAgentSession rebuildFromConfig(String sessionId, SessionConfig config, String userId) {
        var context = userId != null ? ExecutionContext.builder().userId(userId).build() : null;
        var agent = buildAgent(config, null, context);
        return new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
    }

    public void closeSession(String sessionId) {
        var session = sessions.remove(sessionId);
        if (session != null) session.close();
    }

    public List<String> loadToolRefs(String sessionId, List<ToolRef> toolRefs) {
        var session = getSession(sessionId);
        var tools = toolRegistryService.resolveToolRefs(toolRefs);
        if (tools.isEmpty()) {
            throw new NotFoundException("no tools found for refs: " + toolRefs);
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
        List<ToolCall> tools;
        if (definition.publishedConfig != null && definition.publishedConfig.tools != null && !definition.publishedConfig.tools.isEmpty()) {
            tools = toolRegistryService.resolveToolRefs(definition.publishedConfig.tools);
        } else if (definition.tools != null && !definition.tools.isEmpty()) {
            tools = toolRegistryService.resolveToolRefs(definition.tools);
        } else {
            tools = List.of();
        }

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
