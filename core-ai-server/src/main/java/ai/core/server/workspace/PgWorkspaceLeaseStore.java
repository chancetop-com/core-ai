package ai.core.server.workspace;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgWorkspaceLeaseStore implements WorkspaceLeaseStore {
    private static final String TABLE = "app.workspace_leases";
    private static final String SELECT = "SELECT workspace_id, holder_id, lease_token, lease_kind, session_id, "
            + "turn_id, runtime_generation, expires_at, renewed_at FROM " + TABLE;

    private static WorkspaceLeaseRecord toLease(PgWorkspaceLeaseRow row) {
        var lease = new WorkspaceLeaseRecord();
        lease.workspaceId = row.workspaceId;
        lease.holderId = row.holderId;
        lease.leaseToken = row.leaseToken;
        lease.leaseKind = row.leaseKind;
        lease.sessionId = row.sessionId;
        lease.turnId = row.turnId;
        lease.runtimeGeneration = row.runtimeGeneration;
        lease.expiresAt = row.expiresAt;
        lease.renewedAt = row.renewedAt;
        return lease;
    }

    @Inject
    Database database;

    @Override
    public Optional<WorkspaceLeaseRecord> get(String workspaceId) {
        return database.selectOne(SELECT + " WHERE workspace_id = ?", PgWorkspaceLeaseRow.class, workspaceId)
                .map(PgWorkspaceLeaseStore::toLease);
    }

    @Override
    public boolean insert(WorkspaceLeaseRecord lease) {
        return database.execute("INSERT INTO " + TABLE + " (workspace_id, holder_id, lease_token, lease_kind, "
                        + "session_id, turn_id, runtime_generation, expires_at, renewed_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now()) ON CONFLICT DO NOTHING",
                lease.workspaceId, lease.holderId, lease.leaseToken, lease.leaseKind, lease.sessionId,
                lease.turnId, lease.runtimeGeneration, lease.expiresAt, lease.renewedAt) > 0;
    }

    @Override
    public boolean replace(String workspaceId, String expectedToken, WorkspaceLeaseRecord lease) {
        return database.execute("UPDATE " + TABLE + " SET holder_id = ?, lease_token = ?, lease_kind = ?, "
                        + "session_id = ?, turn_id = ?, runtime_generation = ?, expires_at = ?, renewed_at = ?, updated_at = now() "
                        + "WHERE workspace_id = ? AND lease_token = ?",
                lease.holderId, lease.leaseToken, lease.leaseKind, lease.sessionId, lease.turnId,
                lease.runtimeGeneration, lease.expiresAt, lease.renewedAt, workspaceId, expectedToken) > 0;
    }

    @Override
    public boolean renew(String workspaceId, String leaseToken, ZonedDateTime expiresAt) {
        return database.execute("UPDATE " + TABLE + " SET expires_at = ?, renewed_at = ?, updated_at = now() "
                + "WHERE workspace_id = ? AND lease_token = ?", expiresAt, ZonedDateTime.now(), workspaceId, leaseToken) > 0;
    }

    @Override
    public boolean release(String workspaceId, String leaseToken) {
        return database.execute("DELETE FROM " + TABLE + " WHERE workspace_id = ? AND lease_token = ?",
                workspaceId, leaseToken) > 0;
    }

    @Override
    public int releaseBySession(String sessionId) {
        return database.execute("DELETE FROM " + TABLE + " WHERE session_id = ?", sessionId);
    }

    @Override
    public int releaseByWorkspace(String workspaceId) {
        return database.execute("DELETE FROM " + TABLE + " WHERE workspace_id = ?", workspaceId);
    }
}
