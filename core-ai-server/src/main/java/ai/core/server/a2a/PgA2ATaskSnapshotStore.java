package ai.core.server.a2a;

import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * @author stephen
 */
public class PgA2ATaskSnapshotStore implements A2ATaskSnapshotStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(PgA2ATaskSnapshotStore.class);
    private static final String TABLE = "messaging.a2a_tasks";

    @Inject
    public Database database;

    @Override
    public void save(A2ATaskSnapshot snapshot, Duration ttl) {
        try {
            database.execute("INSERT INTO " + TABLE + " (task_id, data, expires_at) VALUES (?, ?::jsonb, now() + make_interval(secs => ?)) "
                    + "ON CONFLICT (task_id) DO UPDATE SET data = EXCLUDED.data, expires_at = EXCLUDED.expires_at",
                snapshot.taskId, JsonUtil.toJson(snapshot), ttl.toSeconds());
        } catch (RuntimeException e) {
            LOGGER.warn("failed to save A2A task snapshot, taskId={}", snapshot.taskId, e);
        }
    }

    @Override
    public A2ATaskSnapshot get(String taskId) {
        try {
            return database.selectOne("SELECT data::text AS data FROM " + TABLE + " WHERE task_id = ? AND expires_at > now()",
                    PgA2ATaskRow.class, taskId)
                .map(row -> JsonUtil.fromJson(A2ATaskSnapshot.class, row.data))
                .orElse(null);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to get A2A task snapshot, taskId={}", taskId, e);
            return null;
        }
    }
}
