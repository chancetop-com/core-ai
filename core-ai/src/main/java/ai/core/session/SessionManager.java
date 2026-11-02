package ai.core.session;

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

    public SessionTitle title(String id) {
        return SessionLoads.title(sessionPersistence, id);
    }
}
