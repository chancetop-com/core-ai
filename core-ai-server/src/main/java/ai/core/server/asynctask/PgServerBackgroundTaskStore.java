package ai.core.server.asynctask;

import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.session.BackgroundTaskStore;
import core.framework.db.Database;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * PostgreSQL store for server-side background tasks ({@code app.server_background_tasks}).
 *
 * @author stephen
 */
public class PgServerBackgroundTaskStore implements BackgroundTaskStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(PgServerBackgroundTaskStore.class);
    private static final String TABLE = "app.server_background_tasks";
    static final String STARTUP_LOST_MESSAGE = "SERVER_TASK_LOST: server restarted while background task was running";

    @Inject
    public Database database;
    @Inject
    public SessionOwnershipRegistry ownershipRegistry;

    @Override
    public void saveRunning(String taskId, String sessionId, String outputRef) {
        var now = ZonedDateTime.now();
        var pod = hostname();
        database.execute("INSERT INTO " + TABLE
                        + " (task_id, session_id, owner_pod, status, output_ref, notified, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'running', ?, FALSE, ?, ?)"
                        + " ON CONFLICT (task_id) DO UPDATE SET"
                        + " session_id = EXCLUDED.session_id, owner_pod = EXCLUDED.owner_pod,"
                        + " status = 'running', result = NULL, error = NULL,"
                        + " output_ref = EXCLUDED.output_ref, notified = FALSE,"
                        + " updated_at = EXCLUDED.updated_at, finished_at = NULL",
                taskId, sessionId, pod, outputRef, now, now);
    }

    @Override
    public void saveTerminal(StoredTask task) {
        var now = ZonedDateTime.now();
        var pod = hostname();
        database.execute("INSERT INTO " + TABLE
                        + " (task_id, session_id, owner_pod, status, result, error, output_ref, notified, created_at, updated_at, finished_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (task_id) DO UPDATE SET"
                        + " status = EXCLUDED.status, result = EXCLUDED.result, error = EXCLUDED.error,"
                        + " output_ref = COALESCE(EXCLUDED.output_ref, " + TABLE + ".output_ref),"
                        + " notified = " + TABLE + ".notified OR EXCLUDED.notified,"
                        + " updated_at = EXCLUDED.updated_at, finished_at = EXCLUDED.finished_at",
                task.taskId(), task.sessionId(), pod, task.status(), task.result(), task.error(),
                task.outputRef(), task.notified(), now, now, now);
    }

    @Override
    public void markNotified(String taskId) {
        database.execute("UPDATE " + TABLE + " SET notified = TRUE, updated_at = ? WHERE task_id = ?",
                ZonedDateTime.now(), taskId);
    }

    @Override
    public Optional<StoredTask> find(String sessionId, String taskId) {
        return database.selectOne(
                "SELECT task_id, session_id, owner_pod, status, result, error, output_ref, notified, created_at, updated_at, finished_at"
                        + " FROM " + TABLE + " WHERE task_id = ? AND session_id = ?",
                PgServerBackgroundTaskRow.class, taskId, sessionId)
                .map(row -> new StoredTask(row.taskId, row.sessionId, row.status, row.result,
                        row.error, row.outputRef, Boolean.TRUE.equals(row.notified)));
    }

    public int markPodOpenTasksLost() {
        var now = ZonedDateTime.now();
        var pod = hostname();
        int updated = database.execute("UPDATE " + TABLE
                        + " SET status = 'lost', error = ?, notified = TRUE, updated_at = ?, finished_at = ?"
                        + " WHERE status = 'running' AND owner_pod = ?",
                STARTUP_LOST_MESSAGE, now, now, pod);
        if (updated > 0) {
            LOGGER.info("marked orphaned server background tasks lost on startup, pod={}, count={}", pod, updated);
        }
        return updated;
    }

    public int purgeTerminalOlderThan(Duration age) {
        var cutoff = ZonedDateTime.now().minus(age);
        return database.execute("DELETE FROM " + TABLE + " WHERE status <> 'running' AND updated_at <= ?", cutoff);
    }

    private String hostname() {
        var host = ownershipRegistry == null ? null : ownershipRegistry.getHostname();
        return host == null || host.isBlank() ? "unknown" : host;
    }
}
