package ai.core.server.sandbox.snapshot;

import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class PgSandboxSnapshotStore implements SandboxSnapshotStore {
    private static final String TABLE = "app.sandbox_snapshots";
    private static final String SELECT = "SELECT id, session_id, user_id, epoch, status, blob_key, sha256, size_bytes, "
        + "file_count, image, runtime_version, created_at, expires_at, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, session_id, user_id, epoch, status, blob_key, "
        + "sha256, size_bytes, file_count, image, runtime_version, created_at, expires_at, data) ";

    private static SandboxSnapshotDoc toDoc(PgSandboxSnapshotRow row) {
        var doc = JsonUtil.fromJson(SandboxSnapshotDoc.class, row.data);
        doc.id = row.id;
        doc.sessionId = row.sessionId;
        doc.userId = row.userId;
        doc.epoch = row.epoch;
        doc.status = row.status;
        doc.blobKey = row.blobKey;
        doc.sha256 = row.sha256;
        doc.sizeBytes = row.sizeBytes;
        doc.fileCount = row.fileCount;
        doc.image = row.image;
        doc.runtimeVersion = row.runtimeVersion;
        doc.createdAt = row.createdAt;
        doc.expiresAt = row.expiresAt;
        return doc;
    }

    @Inject
    public Database database;

    @Override
    public void insert(SandboxSnapshotDoc doc) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(doc));
    }

    @Override
    public SandboxSnapshotDoc findLatestAvailable(String sessionId) {
        return database.select(SELECT + " WHERE session_id = ? AND status = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                PgSandboxSnapshotRow.class, sessionId, SandboxSnapshotDoc.STATUS_AVAILABLE)
            .stream().map(PgSandboxSnapshotStore::toDoc).findFirst().orElse(null);
    }

    @Override
    public List<SandboxSnapshotDoc> listExpired(ZonedDateTime now, int limit) {
        return database.select(SELECT + " WHERE expires_at < ? ORDER BY expires_at LIMIT ?",
                PgSandboxSnapshotRow.class, now, limit)
            .stream().map(PgSandboxSnapshotStore::toDoc).toList();
    }

    @Override
    public List<SandboxSnapshotDoc> listBySession(String sessionId) {
        return database.select(SELECT + " WHERE session_id = ? ORDER BY created_at DESC, id DESC",
                PgSandboxSnapshotRow.class, sessionId)
            .stream().map(PgSandboxSnapshotStore::toDoc).toList();
    }

    @Override
    public boolean markAvailable(String id) {
        return database.execute("UPDATE " + TABLE + " SET status = ? WHERE id = ? AND status = ?",
            SandboxSnapshotDoc.STATUS_AVAILABLE, id, SandboxSnapshotDoc.STATUS_UPLOADING) > 0;
    }

    @Override
    public void tombstone(String id) {
        database.execute("UPDATE " + TABLE + " SET status = ?, expires_at = ? WHERE id = ?",
            SandboxSnapshotDoc.STATUS_DELETED, ZonedDateTime.now(), id);
    }

    @Override
    public void delete(String id) {
        database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    private Object[] params(SandboxSnapshotDoc doc) {
        return new Object[]{
            doc.id,
            doc.sessionId,
            doc.userId,
            doc.epoch,
            doc.status,
            doc.blobKey,
            doc.sha256,
            doc.sizeBytes,
            doc.fileCount,
            doc.image,
            doc.runtimeVersion,
            doc.createdAt,
            doc.expiresAt,
            JsonUtil.toJson(doc)
        };
    }
}
