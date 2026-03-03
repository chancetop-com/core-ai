package ai.core.session;

import ai.core.agent.AgentPersistence;

import java.util.List;

/**
 * @author stephen
 */
public class SessionManager {
    private final SessionPersistence sessionPersistence;

    public SessionManager(SessionPersistence sessionPersistence) {
        this.sessionPersistence = sessionPersistence;
    }

    public List<SessionPersistence.SessionInfo> listSessions() {
        return sessionPersistence.listSessions();
    }

    public String firstUserMessage(String id) {
        return sessionPersistence.load(id)
            .map(AgentPersistence::firstUserMessage)
            .orElse(null);
    }
}
