package ai.core.server.session;

/**
 * Per-user live-session counter. A single row per user keeps acquire and release correct no matter
 * which pod serves the request; the expiry covers a pod that dies before releasing its creates.
 *
 * @author stephen
 */
public interface SessionBudgetStore {
    /** @return false when counting this session would exceed the limit. */
    boolean acquire(String userId, int limit);

    void release(String userId);
}
