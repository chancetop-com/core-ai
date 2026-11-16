package ai.core.server.session;

import core.framework.db.Database;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class PgSessionActivityStore implements SessionActivityStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(PgSessionActivityStore.class);
    private static final String TABLE = "messaging.session_activity";

    @Inject
    public Database database;

    @Override
    public void touch(String sessionId) {
        try {
            database.execute("INSERT INTO " + TABLE + " (session_id, last_activity, expires_at) VALUES (?, ?, now() + make_interval(secs => ?)) "
                    + "ON CONFLICT (session_id) DO UPDATE SET last_activity = EXCLUDED.last_activity, expires_at = EXCLUDED.expires_at",
                sessionId, System.currentTimeMillis(), TTL_SECONDS);
        } catch (RuntimeException e) {
            LOGGER.warn("session activity touch failed, sessionId={}", sessionId, e);
        }
    }

    @Override
    public long lastActivity(String sessionId) {
        try {
            return database.selectOne("SELECT last_activity FROM " + TABLE + " WHERE session_id = ? AND expires_at > now()",
                    PgSessionActivityRow.class, sessionId)
                .map(row -> row.lastActivity)
                .orElse(0L);
        } catch (RuntimeException e) {
            LOGGER.warn("session activity read failed, sessionId={}", sessionId, e);
            return 0L;
        }
    }
}
