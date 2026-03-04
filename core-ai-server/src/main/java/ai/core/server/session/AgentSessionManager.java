package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.SessionConfig;
import ai.core.llm.LLMProviders;
import ai.core.persistence.PersistenceProviders;
import ai.core.session.InProcessAgentSession;
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
        var autoApproveAll = Boolean.TRUE.equals(config.autoApproveAll);
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, null);
        sessions.put(sessionId, session);
        return sessionId;
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

    private Agent buildAgent(SessionConfig config) {
        var builder = Agent.builder()
                .llmProvider(llmProviders.getProvider())
                .toolCalls(BuiltinTools.ALL)
                .temperature(config.temperature != null ? config.temperature : 0.8);

        if (config.systemPrompt != null) {
            builder.systemPrompt(config.systemPrompt);
        } else {
            builder.systemPrompt("You are a helpful AI assistant.");
        }
        if (config.model != null) {
            builder.model(config.model);
        }
        if (config.maxTurns != null) {
            builder.maxTurn(config.maxTurns);
        }

        var provider = persistenceProviders.getDefaultPersistenceProvider();
        if (provider != null) {
            builder.persistenceProvider(provider);
        }

        return builder.build();
    }
}
