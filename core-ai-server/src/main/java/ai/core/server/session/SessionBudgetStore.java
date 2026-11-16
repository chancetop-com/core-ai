package ai.core.server.session;

/**
 * Per-user live-session counter. Counted across pods; the PostgreSQL implementation keeps one row
 * per (user, pod) and only counts rows whose pod heartbeat is fresh.
 *
 * @author stephen
 */
public interface SessionBudgetStore {
    /** @return false when counting this session would exceed the limit. */
    boolean acquire(String userId, String podId, int limit);

    void release(String userId, String podId);
}
