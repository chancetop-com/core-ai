package ai.core.server.session;

import ai.core.server.messaging.PgLeaseCountRow;
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
    private static final int TTL_SECONDS = 24 * 3600;

    @Inject
    public Database database;

    @Override
    public boolean acquire(String userId, int limit) {
        try {
            database.execute("INSERT INTO " + TABLE + " (user_id, count, expires_at) VALUES (?, 1, now() + make_interval(secs => ?)) "
                    + "ON CONFLICT (user_id) DO UPDATE SET "
                    + "count = CASE WHEN " + TABLE + ".expires_at < now() THEN 1 ELSE " + TABLE + ".count + 1 END, "
                    + "expires_at = now() + make_interval(secs => ?)",
                userId, TTL_SECONDS, TTL_SECONDS);
            var total = database.selectOne("SELECT count::bigint AS count FROM " + TABLE + " WHERE user_id = ? AND expires_at > now()",
                PgLeaseCountRow.class, userId)
                .map(row -> row.count)
                .orElse(0L);
            if (total > limit) {
                database.execute("UPDATE " + TABLE + " SET count = count - 1 WHERE user_id = ?", userId);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to count user sessions (PostgreSQL unavailable), allowing the session, userId={}", userId, e);
            return true;
        }
    }

    @Override
    public void release(String userId) {
        try {
            database.execute("UPDATE " + TABLE + " SET count = count - 1, expires_at = now() + make_interval(secs => ?) "
                + "WHERE user_id = ?", TTL_SECONDS, userId);
            database.execute("DELETE FROM " + TABLE + " WHERE user_id = ? AND count <= 0", userId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to release user session budget (PostgreSQL unavailable), userId={}", userId, e);
        }
    }
}
