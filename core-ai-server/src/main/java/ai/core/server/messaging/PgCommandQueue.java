package ai.core.server.messaging;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-backed asynchronous command queue. It is enabled only by
 * {@code sys.command.backend=postgres}; Redis remains the mixed-version path.
 *
 * @author stephen
 */
public class PgCommandQueue {
    private static final String SELECT_PENDING = "SELECT id, type, session_id, user_id, payload, request_id, target_pod, attempts "
            + "FROM messaging.commands "
            + "WHERE status = ? AND available_at <= now() AND (target_pod = ? OR target_pod IS NULL) "
            + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED";

    @Inject
    public Database database;

    public void publish(SessionCommand command, String targetPod) {
        database.execute("INSERT INTO messaging.commands (type, session_id, user_id, payload, request_id, target_pod) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                command.type().name(), command.sessionId(), command.userId(), command.payload(),
                command.requestId(), targetPod);
    }

    public List<ClaimedCommand> claim(String podId, int limit) {
        var claimed = new ArrayList<ClaimedCommand>();
        try (var transaction = database.beginTransaction()) {
            var rows = database.select(SELECT_PENDING, PgCommandRow.class, "pending", podId, limit);
            for (var row : rows) {
                var updated = database.execute("UPDATE messaging.commands SET status = ?, claimed_by = ?, "
                                + "claimed_at = now(), attempts = attempts + 1 WHERE id = ? AND status = ?",
                        "processing", podId, row.id, "pending");
                if (updated > 0) {
                    claimed.add(new ClaimedCommand(row.id, toCommand(row), row.targetPod, row.attempts == null ? 1 : row.attempts + 1));
                }
            }
            transaction.commit();
        }
        return claimed;
    }

    public void complete(long id, String podId) {
        database.execute("DELETE FROM messaging.commands WHERE id = ? AND claimed_by = ?", id, podId);
    }

    public void fail(long id, String podId, int attempts, Throwable error) {
        if (attempts >= 3) {
            database.execute("UPDATE messaging.commands SET status = ?, claimed_by = NULL, claimed_at = NULL, "
                            + "last_error = ? WHERE id = ? AND claimed_by = ?",
                    "failed", message(error), id, podId);
            return;
        }
        database.execute("UPDATE messaging.commands SET status = ?, claimed_by = NULL, claimed_at = NULL, "
                        + "available_at = now() + make_interval(secs => ?), last_error = ? WHERE id = ? AND claimed_by = ?",
                "pending", retryDelaySeconds(attempts), message(error), id, podId);
    }

    public void retryLater(long id, String podId, int attempts, long delaySeconds) {
        if (attempts >= 3) {
            database.execute("UPDATE messaging.commands SET status = ?, claimed_by = NULL, claimed_at = NULL, "
                            + "last_error = ? WHERE id = ? AND claimed_by = ?",
                    "failed", "unowned session is owned by another pod", id, podId);
            return;
        }
        database.execute("UPDATE messaging.commands SET status = ?, claimed_by = NULL, claimed_at = NULL, "
                        + "available_at = now() + make_interval(secs => ?) WHERE id = ? AND claimed_by = ?",
                "pending", delaySeconds, id, podId);
    }

    public void releaseClaimedBy(String podId) {
        database.execute("UPDATE messaging.commands SET status = ?, claimed_by = NULL, claimed_at = NULL, "
                + "available_at = now() WHERE status = ? AND claimed_by = ?", "pending", "processing", podId);
    }

    public void cleanup() {
        database.execute("UPDATE messaging.commands SET status = ?, claimed_by = NULL, claimed_at = NULL, "
                + "available_at = now() WHERE status = ? AND NOT EXISTS ("
                + "SELECT 1 FROM messaging.pods p WHERE p.pod_id = messaging.commands.claimed_by "
                + "AND p.heartbeat_at > now() - make_interval(secs => 30))", "pending", "processing");
        database.execute("DELETE FROM messaging.commands WHERE status = ? AND created_at < now() - make_interval(days => ?)",
                "failed", 1);
    }

    private SessionCommand toCommand(PgCommandRow row) {
        return new SessionCommand(CommandType.valueOf(row.type), row.sessionId, row.userId, row.payload, row.requestId);
    }

    private long retryDelaySeconds(int attempts) {
        return attempts <= 1 ? 1 : 5;
    }

    private String message(Throwable error) {
        if (error == null) return null;
        var value = error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getName();
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    public record ClaimedCommand(long id, SessionCommand command, String targetPod, int attempts) {
    }
}
