package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.SessionConfig;
import ai.core.llm.LLMProviders;
import ai.core.persistence.PersistenceProviders;
import ai.core.server.domain.AgentDefinition;
import ai.core.session.InProcessAgentSession;
import ai.core.session.InMemoryToolPermissionStore;
import ai.core.tool.BuiltinTools;
import core.framework.inject.Inject;

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

    public String createSession(SessionConfig config) {
        var sessionId = UUID.randomUUID().toString();
        var agent = buildAgent(config);
        var autoApproveAll = config != null && Boolean.TRUE.equals(config.autoApproveAll);
        var permissionStore = new InMemoryToolPermissionStore();
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore);
        sessions.put(sessionId, session);
        return sessionId;
    }

    public String createSessionFromAgent(AgentDefinition definition, SessionConfig overrides) {
        var config = toSessionConfig(definition);
        if (overrides != null) {
            if (overrides.model != null) config.model = overrides.model;
            if (overrides.temperature != null) config.temperature = overrides.temperature;
            if (overrides.systemPrompt != null) config.systemPrompt = overrides.systemPrompt;
            if (overrides.maxTurns != null) config.maxTurns = overrides.maxTurns;
            if (overrides.autoApproveAll != null) config.autoApproveAll = overrides.autoApproveAll;
        }
        return createSession(config);
    }

    public InProcessAgentSession getSession(String sessionId) {
        var session = sessions.get(sessionId);
        if (session == null) throw new RuntimeException("session not found, sessionId=" + sessionId);
        return session;
    }

    public void closeSession(String sessionId) {
        var session = sessions.remove(sessionId);
        if (session != null) session.close();
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

    private Agent buildAgent(SessionConfig config) {
        var builder = Agent.builder()
                .llmProvider(llmProviders.getProvider())
                .toolCalls(BuiltinTools.ALL)
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

        var provider = persistenceProviders.getDefaultPersistenceProvider();
        if (provider != null) {
            builder.persistenceProvider(provider);
        }

        return builder.build();
    }
}
