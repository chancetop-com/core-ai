package ai.core.server.workspace;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgWorkspaceStore implements WorkspaceStore {
    private static final String TABLE = "app.workspaces";
    private static final String SELECT = "SELECT id, owner_user_id, name, agent_id, is_default, volume_status, "
            + "runtime_status, active_volume_generation, size_gb, storage_class, profile, mount_path, image, "
            + "runtime_version, env_json::text AS env_json, bootstrap_script, start_script, network_enabled, "
            + "network_profile, idle_sleep_minutes, runtime_generation, last_used_at, last_provisioned_at, "
            + "error_code, error_message, deleted_at, created_at, updated_at FROM " + TABLE;


    private static WorkspaceRecord toWorkspace(PgWorkspaceRow row) {
        var workspace = new WorkspaceRecord();
        workspace.id = row.id;
        workspace.ownerUserId = row.ownerUserId;
        workspace.name = row.name;
        workspace.agentId = row.agentId;
        workspace.isDefault = Boolean.TRUE.equals(row.isDefault);
        workspace.volumeStatus = WorkspaceVolumeStatus.valueOf(row.volumeStatus);
        workspace.runtimeStatus = WorkspaceRuntimeStatus.valueOf(row.runtimeStatus);
        workspace.activeVolumeGeneration = row.activeVolumeGeneration;
        workspace.sizeGb = row.sizeGb;
        workspace.storageClass = row.storageClass;
        workspace.profile = WorkspaceProfile.of(row.profile);
        workspace.mountPath = row.mountPath;
        workspace.image = row.image;
        workspace.runtimeVersion = row.runtimeVersion;
        workspace.env = row.envJson == null || row.envJson.isBlank()
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(JsonUtil.fromJson(ENV_TYPE, row.envJson));
        workspace.bootstrapScript = row.bootstrapScript;
        workspace.startScript = row.startScript;
        workspace.networkEnabled = Boolean.TRUE.equals(row.networkEnabled);
        workspace.networkProfile = row.networkProfile;
        workspace.idleSleepMinutes = row.idleSleepMinutes;
        workspace.runtimeGeneration = row.runtimeGeneration == null ? 0 : row.runtimeGeneration;
        workspace.lastUsedAt = row.lastUsedAt;
        workspace.lastProvisionedAt = row.lastProvisionedAt;
        workspace.errorCode = row.errorCode;
        workspace.errorMessage = row.errorMessage;
        workspace.deletedAt = row.deletedAt;
        workspace.createdAt = row.createdAt;
        workspace.updatedAt = row.updatedAt;
        return workspace;
    }

    private static final TypeReference<Map<String, String>> ENV_TYPE = new TypeReference<>() {
    };

    @Inject
    Database database;

    @Override
    public Optional<WorkspaceRecord> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgWorkspaceRow.class, id)
                .map(PgWorkspaceStore::toWorkspace);
    }

    @Override
    public Optional<WorkspaceRecord> findDefault(String ownerUserId, String agentId) {
        return database.selectOne(SELECT + " WHERE owner_user_id = ? AND agent_id = ? AND is_default AND deleted_at IS NULL",
                PgWorkspaceRow.class, ownerUserId, agentId).map(PgWorkspaceStore::toWorkspace);
    }

    @Override
    public Optional<WorkspaceRecord> findByName(String ownerUserId, String name) {
        return database.selectOne(SELECT + " WHERE owner_user_id = ? AND name = ? AND deleted_at IS NULL",
                PgWorkspaceRow.class, ownerUserId, name).map(PgWorkspaceStore::toWorkspace);
    }

    @Override
    public List<WorkspaceRecord> listByOwner(String ownerUserId) {
        return database.select(SELECT + " WHERE owner_user_id = ? AND deleted_at IS NULL ORDER BY updated_at DESC",
                PgWorkspaceRow.class, ownerUserId).stream().map(PgWorkspaceStore::toWorkspace).toList();
    }

    @Override
    public List<WorkspaceRecord> listActive() {
        return database.select(SELECT + " WHERE deleted_at IS NULL ORDER BY updated_at", PgWorkspaceRow.class)
                .stream().map(PgWorkspaceStore::toWorkspace).toList();
    }

    @Override
    public List<WorkspaceRecord> listDeletedBefore(ZonedDateTime cutoff) {
        return database.select(SELECT + " WHERE deleted_at IS NOT NULL AND deleted_at < ?", PgWorkspaceRow.class, cutoff)
                .stream().map(PgWorkspaceStore::toWorkspace).toList();
    }

    @Override
    public boolean insertIfAbsent(WorkspaceRecord workspace) {
        return database.execute("INSERT INTO " + TABLE + " (id, owner_user_id, name, agent_id, is_default, "
                        + "volume_status, runtime_status, active_volume_generation, size_gb, storage_class, profile, "
                        + "mount_path, image, runtime_version, env_json, bootstrap_script, start_script, network_enabled, "
                        + "network_profile, idle_sleep_minutes, runtime_generation, last_used_at, last_provisioned_at, "
                        + "error_code, error_message, deleted_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT DO NOTHING",
                workspace.id, workspace.ownerUserId, workspace.name, workspace.agentId, workspace.isDefault,
                workspace.volumeStatus.name(), workspace.runtimeStatus.name(), workspace.activeVolumeGeneration,
                workspace.sizeGb, workspace.storageClass, workspace.profile.id, workspace.mountPath, workspace.image,
                workspace.runtimeVersion, JsonUtil.toJson(workspace.env), workspace.bootstrapScript, workspace.startScript,
                workspace.networkEnabled, workspace.networkProfile, workspace.idleSleepMinutes,
                workspace.runtimeGeneration, workspace.lastUsedAt, workspace.lastProvisionedAt, workspace.errorCode,
                workspace.errorMessage, workspace.deletedAt, workspace.createdAt, workspace.updatedAt) > 0;
    }

    @Override
    public void update(WorkspaceRecord workspace) {
        database.execute("UPDATE " + TABLE + " SET name = ?, agent_id = ?, is_default = ?, volume_status = ?, "
                        + "runtime_status = ?, active_volume_generation = ?, size_gb = ?, storage_class = ?, profile = ?, "
                        + "mount_path = ?, image = ?, runtime_version = ?, env_json = ?::jsonb, bootstrap_script = ?, "
                        + "start_script = ?, network_enabled = ?, network_profile = ?, idle_sleep_minutes = ?, "
                        + "runtime_generation = ?, last_used_at = ?, last_provisioned_at = ?, error_code = ?, "
                        + "error_message = ?, deleted_at = ?, updated_at = ? WHERE id = ?",
                workspace.name, workspace.agentId, workspace.isDefault, workspace.volumeStatus.name(),
                workspace.runtimeStatus.name(), workspace.activeVolumeGeneration, workspace.sizeGb,
                workspace.storageClass, workspace.profile.id, workspace.mountPath, workspace.image,
                workspace.runtimeVersion, JsonUtil.toJson(workspace.env), workspace.bootstrapScript,
                workspace.startScript, workspace.networkEnabled, workspace.networkProfile, workspace.idleSleepMinutes,
                workspace.runtimeGeneration, workspace.lastUsedAt, workspace.lastProvisionedAt, workspace.errorCode,
                workspace.errorMessage, workspace.deletedAt, ZonedDateTime.now(), workspace.id);
    }

    @Override
    public void updateVolumeState(String id, WorkspaceVolumeStatus status, Long activeVolumeGeneration,
                                  String errorCode, String errorMessage) {
        database.execute("UPDATE " + TABLE + " SET volume_status = ?, active_volume_generation = ?, error_code = ?, "
                + "error_message = ?, updated_at = now() WHERE id = ?",
                status.name(), activeVolumeGeneration, errorCode, errorMessage, id);
    }

    @Override
    public void updateRuntimeState(String id, WorkspaceRuntimeStatus status, long runtimeGeneration,
                                   String errorCode, String errorMessage) {
        database.execute("UPDATE " + TABLE + " SET runtime_status = ?, runtime_generation = ?, error_code = ?, "
                + "error_message = ?, updated_at = now() WHERE id = ?",
                status.name(), runtimeGeneration, errorCode, errorMessage, id);
    }

    @Override
    public void touchLastUsed(String id, ZonedDateTime at) {
        database.execute("UPDATE " + TABLE + " SET last_used_at = ?, updated_at = now() WHERE id = ?", at, id);
    }

    @Override
    public boolean softDelete(String id, ZonedDateTime at) {
        return database.execute("UPDATE " + TABLE + " SET deleted_at = ?, is_default = FALSE, updated_at = now() "
                + "WHERE id = ? AND deleted_at IS NULL", at, id) > 0;
    }

    @Override
    public boolean restore(String id) {
        return database.execute("UPDATE " + TABLE + " SET deleted_at = NULL, updated_at = now() "
                + "WHERE id = ? AND deleted_at IS NOT NULL", id) > 0;
    }

    @Override
    public boolean purge(String id) {
        return database.execute("DELETE FROM " + TABLE + " WHERE id = ? AND deleted_at IS NOT NULL", id) > 0;
    }

    @Override
    public void makeDefault(String ownerUserId, String agentId, String id) {
        try (var transaction = database.beginTransaction()) {
            database.execute("UPDATE " + TABLE + " SET is_default = FALSE, updated_at = now() "
                    + "WHERE owner_user_id = ? AND agent_id = ? AND is_default AND deleted_at IS NULL AND id <> ?",
                    ownerUserId, agentId, id);
            database.execute("UPDATE " + TABLE + " SET is_default = TRUE, updated_at = now() WHERE id = ?", id);
            transaction.commit();
        }
    }
}
