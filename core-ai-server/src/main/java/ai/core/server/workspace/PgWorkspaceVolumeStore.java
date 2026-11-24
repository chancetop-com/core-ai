package ai.core.server.workspace;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgWorkspaceVolumeStore implements WorkspaceVolumeStore {
    private static final String TABLE = "app.workspace_volumes";
    private static final String SELECT = "SELECT workspace_id, volume_generation, pvc_name, provider, size_gb, "
            + "storage_class, status, node_name, zone, bound_at, deleted_at, created_at, updated_at FROM " + TABLE;

    private static WorkspaceVolumeRecord toVolume(PgWorkspaceVolumeRow row) {
        var volume = new WorkspaceVolumeRecord();
        volume.workspaceId = row.workspaceId;
        volume.volumeGeneration = row.volumeGeneration;
        volume.pvcName = row.pvcName;
        volume.provider = row.provider;
        volume.sizeGb = row.sizeGb;
        volume.storageClass = row.storageClass;
        volume.status = WorkspaceVolumeStatus.valueOf(row.status);
        volume.nodeName = row.nodeName;
        volume.zone = row.zone;
        volume.boundAt = row.boundAt;
        volume.deletedAt = row.deletedAt;
        volume.createdAt = row.createdAt;
        volume.updatedAt = row.updatedAt;
        return volume;
    }

    @Inject
    Database database;

    @Override
    public Optional<WorkspaceVolumeRecord> get(String workspaceId, long volumeGeneration) {
        return database.selectOne(SELECT + " WHERE workspace_id = ? AND volume_generation = ?",
                PgWorkspaceVolumeRow.class, workspaceId, volumeGeneration).map(PgWorkspaceVolumeStore::toVolume);
    }

    @Override
    public Optional<WorkspaceVolumeRecord> active(String workspaceId) {
        return database.selectOne(SELECT + " WHERE workspace_id = ? AND status IN ('PROVISIONING', 'READY', 'ERROR') "
                        + "ORDER BY volume_generation DESC LIMIT 1",
                PgWorkspaceVolumeRow.class, workspaceId).map(PgWorkspaceVolumeStore::toVolume);
    }

    @Override
    public List<WorkspaceVolumeRecord> listByWorkspace(String workspaceId) {
        return database.select(SELECT + " WHERE workspace_id = ? ORDER BY volume_generation DESC",
                PgWorkspaceVolumeRow.class, workspaceId).stream().map(PgWorkspaceVolumeStore::toVolume).toList();
    }

    @Override
    public List<WorkspaceVolumeRecord> listByStatus(WorkspaceVolumeStatus status) {
        return database.select(SELECT + " WHERE status = ? ORDER BY updated_at", PgWorkspaceVolumeRow.class, status.name())
                .stream().map(PgWorkspaceVolumeStore::toVolume).toList();
    }

    @Override
    public boolean insertIfAbsent(WorkspaceVolumeRecord volume) {
        return database.execute("INSERT INTO " + TABLE + " (workspace_id, volume_generation, pvc_name, provider, "
                        + "size_gb, storage_class, status, node_name, zone, bound_at, deleted_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                volume.workspaceId, volume.volumeGeneration, volume.pvcName, volume.provider, volume.sizeGb,
                volume.storageClass, volume.status.name(), volume.nodeName, volume.zone, volume.boundAt,
                volume.deletedAt, volume.createdAt, volume.updatedAt) > 0;
    }

    @Override
    public void update(WorkspaceVolumeRecord volume) {
        database.execute("UPDATE " + TABLE + " SET pvc_name = ?, provider = ?, size_gb = ?, storage_class = ?, "
                        + "status = ?, node_name = ?, zone = ?, bound_at = ?, deleted_at = ?, updated_at = ? "
                        + "WHERE workspace_id = ? AND volume_generation = ?",
                volume.pvcName, volume.provider, volume.sizeGb, volume.storageClass, volume.status.name(),
                volume.nodeName, volume.zone, volume.boundAt, volume.deletedAt, ZonedDateTime.now(),
                volume.workspaceId, volume.volumeGeneration);
    }

    @Override
    public void updateStatus(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status) {
        database.execute("UPDATE " + TABLE + " SET status = ?, updated_at = now() "
                + "WHERE workspace_id = ? AND volume_generation = ?", status.name(), workspaceId, volumeGeneration);
    }

    @Override
    public void markDeleted(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status) {
        database.execute("UPDATE " + TABLE + " SET status = ?, deleted_at = COALESCE(deleted_at, now()), "
                + "updated_at = now() WHERE workspace_id = ? AND volume_generation = ?",
                status.name(), workspaceId, volumeGeneration);
    }
}
