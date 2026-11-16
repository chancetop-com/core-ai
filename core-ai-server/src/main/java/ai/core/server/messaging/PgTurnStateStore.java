package ai.core.server.messaging;

import core.framework.db.Database;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class PgTurnStateStore implements TurnStateStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(PgTurnStateStore.class);

    @Inject
    public Database database;

    @Override
    public void markRunning(String sessionId, String userId, String ownerPod) {
        try {
            database.execute("INSERT INTO messaging.turn_states (session_id, owner_pod, user_id, started_at) "
                    + "VALUES (?, ?, ?, now()) "
                    + "ON CONFLICT (session_id) DO UPDATE SET owner_pod = EXCLUDED.owner_pod, "
                    + "user_id = EXCLUDED.user_id, started_at = EXCLUDED.started_at",
                sessionId, ownerPod, userId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to write turn state (PostgreSQL unavailable), sessionId={}", sessionId, e);
        }
    }

    @Override
    public void clear(String sessionId, String userId, String ownerPod) {
        try {
            database.execute("DELETE FROM messaging.turn_states WHERE session_id = ? AND owner_pod = ?",
                sessionId, ownerPod);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to clear turn state (PostgreSQL unavailable), sessionId={}", sessionId, e);
        }
    }

    @Override
    public void renew(String sessionId, String userId) {
        // validity follows the owner pod's heartbeat, nothing to extend per turn
    }

    @Override
    public TurnStateRegistry.TurnLiveness liveness(String sessionId) {
        try {
            var row = database.selectOne("SELECT count(*) AS count FROM messaging.turn_states t "
                    + "JOIN messaging.pods p ON p.pod_id = t.owner_pod "
                    + "WHERE t.session_id = ? AND p.heartbeat_at > now() - make_interval(secs => ?)",
                PgLeaseCountRow.class, sessionId, PgOwnershipStore.LIVENESS_SECONDS);
            return row.map(value -> value.count).orElse(0L) > 0
                ? TurnStateRegistry.TurnLiveness.RUNNING
                : TurnStateRegistry.TurnLiveness.NOT_RUNNING;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to read turn state (PostgreSQL unavailable), sessionId={}", sessionId, e);
            return TurnStateRegistry.TurnLiveness.UNKNOWN;
        }
    }

    @Override
    public int inFlightTurns(String userId) {
        if (userId == null) return 0;
        try {
            return database.selectOne("SELECT count(*) AS count FROM messaging.turn_states t "
                    + "JOIN messaging.pods p ON p.pod_id = t.owner_pod "
                    + "WHERE t.user_id = ? AND p.heartbeat_at > now() - make_interval(secs => ?)",
                PgLeaseCountRow.class, userId, PgOwnershipStore.LIVENESS_SECONDS)
                .map(row -> row.count == null ? 0 : row.count.intValue())
                .orElse(0);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to read in-flight turns (PostgreSQL unavailable), userId={}", userId, e);
            return 0;
        }
    }
}
