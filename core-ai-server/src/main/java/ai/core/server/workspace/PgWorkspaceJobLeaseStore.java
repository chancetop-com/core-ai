package ai.core.server.workspace;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * Cross-pod singleton gate for the workspace lifecycle sweeps. Every replica schedules the job, but
 * only one of them takes the row each tick; a dead holder is replaced once its TTL expires.
 *
 * @author stephen
 */
public class PgWorkspaceJobLeaseStore {
    private static final String TABLE = "app.workspace_job_leases";

    @Inject
    Database database;

    public boolean tryAcquire(String jobName, String holderId, Duration ttl) {
        return database.execute("INSERT INTO " + TABLE + " (job_name, holder_id, expires_at, updated_at) "
                        + "VALUES (?, ?, ?, now()) ON CONFLICT (job_name) DO UPDATE SET holder_id = EXCLUDED.holder_id, "
                        + "expires_at = EXCLUDED.expires_at, updated_at = now() "
                        + "WHERE app.workspace_job_leases.expires_at < now()",
                jobName, holderId, ZonedDateTime.now().plus(ttl)) > 0;
    }

    public void release(String jobName, String holderId) {
        database.execute("DELETE FROM " + TABLE + " WHERE job_name = ? AND holder_id = ?", jobName, holderId);
    }
}
