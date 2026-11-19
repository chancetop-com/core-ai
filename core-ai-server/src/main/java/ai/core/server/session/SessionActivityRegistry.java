package ai.core.server.session;

/**
 * Durable cross-pod session activity timestamps.
 * Terminal requests may land on a non-owner pod whose in-memory activity map
 * is invisible to the owner pod's idle cleanup; the shared store is the only
 * channel that survives load balancing.
 *
 * @author xander
 */
public class SessionActivityRegistry {
    public static SessionActivityRegistry of(SessionActivityStore store) {
        return new SessionActivityRegistry(store);
    }

    private final SessionActivityStore store;

    public SessionActivityRegistry(SessionActivityStore store) {
        this.store = store;
    }

    public void touch(String sessionId) {
        store.touch(sessionId);
    }

    public long lastActivity(String sessionId) {
        return store.lastActivity(sessionId);
    }
}
