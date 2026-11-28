package ai.core.server.workspace;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link WorkspaceArchiveStore}.
 *
 * @author stephen
 */
public class PgWorkspaceArchiveStore implements WorkspaceArchiveStore {
    private static final String TABLE = "app.workspace_archives";
    private static final String SELECT = "SELECT id, workspace_id, volume_generation, blob_key, sha256, "
            + "size_bytes, file_count, image, status, failure_reason, created_by, created_at, updated_at FROM " + TABLE;

    private static WorkspaceArchiveRecord toArchive(PgWorkspaceArchiveRow row) {
        var archive = new WorkspaceArchiveRecord();
        archive.id = row.id;
        archive.workspaceId = row.workspaceId;
        archive.volumeGeneration = row.volumeGeneration == null ? 0L : row.volumeGeneration;
        archive.blobKey = row.blobKey;
        archive.sha256 = row.sha256;
        archive.sizeBytes = row.sizeBytes == null ? 0L : row.sizeBytes;
        archive.fileCount = row.fileCount;
        archive.image = row.image;
        archive.status = WorkspaceArchiveStatus.valueOf(row.status);
        archive.failureReason = row.failureReason;
        archive.createdBy = row.createdBy;
        archive.createdAt = row.createdAt;
        archive.updatedAt = row.updatedAt;
        return archive;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    @Inject
    Database database;

    @Override
    public Optional<WorkspaceArchiveRecord> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgWorkspaceArchiveRow.class, id)
                .map(PgWorkspaceArchiveStore::toArchive);
    }

    @Override
    public List<WorkspaceArchiveRecord> listVisible(String workspaceId) {
        return database.select(SELECT + " WHERE workspace_id = ? AND status IN (?, ?) ORDER BY created_at DESC",
                        PgWorkspaceArchiveRow.class, workspaceId,
                        WorkspaceArchiveStatus.READY.name(), WorkspaceArchiveStatus.FAILED.name())
                .stream().map(PgWorkspaceArchiveStore::toArchive).toList();
    }

    @Override
    public List<WorkspaceArchiveRecord> listByWorkspace(String workspaceId) {
        return database.select(SELECT + " WHERE workspace_id = ? ORDER BY created_at DESC",
                        PgWorkspaceArchiveRow.class, workspaceId)
                .stream().map(PgWorkspaceArchiveStore::toArchive).toList();
    }

    @Override
    public List<WorkspaceArchiveRecord> listExpired(ZonedDateTime createdBefore, int limit) {
        return database.select(SELECT + " WHERE status <> ? AND created_at < ? ORDER BY created_at ASC LIMIT ?",
                        PgWorkspaceArchiveRow.class, WorkspaceArchiveStatus.DELETED.name(), createdBefore, Math.max(1, limit))
                .stream().map(PgWorkspaceArchiveStore::toArchive).toList();
    }

    @Override
    public Map<String, WorkspaceArchiveSummary> summarizeReady(List<String> workspaceIds) {
        if (workspaceIds == null || workspaceIds.isEmpty()) return Map.of();
        var params = new Object[workspaceIds.size() + 1];
        params[0] = WorkspaceArchiveStatus.READY.name();
        for (int i = 0; i < workspaceIds.size(); i++) {
            params[i + 1] = workspaceIds.get(i);
        }
        var rows = database.select(
                "SELECT workspace_id, COUNT(*) AS archive_count, MAX(created_at) AS latest_archive_at FROM " + TABLE
                        + " WHERE status = ? AND workspace_id IN (" + placeholders(workspaceIds.size()) + ") GROUP BY workspace_id",
                PgWorkspaceArchiveSummaryRow.class, params);
        var result = new LinkedHashMap<String, WorkspaceArchiveSummary>(rows.size());
        for (var row : rows) {
            result.put(row.workspaceId, new WorkspaceArchiveSummary(
                    row.archiveCount == null ? 0L : row.archiveCount, row.latestArchiveAt));
        }
        return result;
    }

    @Override
    public boolean insertIfAbsent(WorkspaceArchiveRecord archive) {
        return database.execute("INSERT INTO " + TABLE + " (id, workspace_id, volume_generation, blob_key, sha256, "
                        + "size_bytes, file_count, image, status, failure_reason, created_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                archive.id, archive.workspaceId, Math.toIntExact(archive.volumeGeneration), archive.blobKey,
                archive.sha256, archive.sizeBytes, archive.fileCount, archive.image, archive.status.name(),
                archive.failureReason, archive.createdBy, archive.createdAt, archive.updatedAt) > 0;
    }

    @Override
    public boolean markReady(String id, String sha256, long sizeBytes, Long fileCount) {
        return database.execute("UPDATE " + TABLE + " SET status = ?, sha256 = ?, size_bytes = ?, file_count = ?, "
                        + "failure_reason = NULL, updated_at = now() WHERE id = ? AND status = ?",
                WorkspaceArchiveStatus.READY.name(), sha256, sizeBytes, fileCount,
                id, WorkspaceArchiveStatus.CREATING.name()) > 0;
    }

    @Override
    public boolean markFailed(String id, String failureReason) {
        return database.execute("UPDATE " + TABLE + " SET status = ?, failure_reason = ?, updated_at = now() "
                        + "WHERE id = ? AND status = ?",
                WorkspaceArchiveStatus.FAILED.name(), failureReason,
                id, WorkspaceArchiveStatus.CREATING.name()) > 0;
    }

    @Override
    public boolean markDeleted(String id) {
        return database.execute("UPDATE " + TABLE + " SET status = ?, updated_at = now() WHERE id = ? AND status <> ?",
                WorkspaceArchiveStatus.DELETED.name(), id, WorkspaceArchiveStatus.DELETED.name()) > 0;
    }
}
