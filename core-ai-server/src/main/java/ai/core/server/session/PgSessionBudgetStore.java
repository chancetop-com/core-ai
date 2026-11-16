package ai.core.server.session;

import ai.core.server.messaging.PgLeaseCountRow;
import ai.core.server.messaging.PgOwnershipStore;
import core.framework.db.Database;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class PgSessionBudgetStore implements SessionBudgetStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(PgSessionBudgetStore.class);
    private static final String TABLE = "messaging.user_session_counts";

    @Inject
    public Database database;

    @Override
    public boolean acquire(String userId, String podId, int limit) {
        try {
            database.execute("INSERT INTO " + TABLE + " (user_id, pod_id, count) VALUES (?, ?, 1) "
                + "ON CONFLICT (user_id, pod_id) DO UPDATE SET count = " + TABLE + ".count + 1", userId, podId);
            var total = database.selectOne("SELECT COALESCE(SUM(c.count), 0) AS count FROM " + TABLE + " c "
                    + "JOIN messaging.pods p ON p.pod_id = c.pod_id "
                    + "WHERE c.user_id = ? AND p.heartbeat_at > now() - make_interval(secs => ?)",
                PgLeaseCountRow.class, userId, PgOwnershipStore.LIVENESS_SECONDS)
                .map(row -> row.count)
                .orElse(0L);
            if (total > limit) {
                release(userId, podId);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to count user sessions (PostgreSQL unavailable), allowing the session, userId={}", userId, e);
            return true;
        }
    }

    @Override
    public void release(String userId, String podId) {
        try {
            database.execute("UPDATE " + TABLE + " SET count = count - 1 WHERE user_id = ? AND pod_id = ?", userId, podId);
            database.execute("DELETE FROM " + TABLE + " WHERE user_id = ? AND pod_id = ? AND count <= 0", userId, podId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to release user session budget (PostgreSQL unavailable), userId={}", userId, e);
        }
    }
}
