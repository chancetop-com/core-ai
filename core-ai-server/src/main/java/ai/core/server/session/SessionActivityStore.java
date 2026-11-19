package ai.core.server.session;

/**
 * Cross-pod session activity timestamps, written when a request touches a session and read by the
 * owner pod's idle cleanup. Backed by PostgreSQL so every pod sees the same timestamp.
 *
 * @author stephen
 */
public interface SessionActivityStore {
    int TTL_SECONDS = 4500;

    void touch(String sessionId);

    long lastActivity(String sessionId);
}
