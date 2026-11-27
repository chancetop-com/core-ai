package ai.core.server.workspace;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkspaceRow {
    @Column(name = "id")
    public String id;

    @Column(name = "owner_user_id")
    public String ownerUserId;

    @Column(name = "name")
    public String name;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "is_default")
    public Boolean isDefault;

    @Column(name = "volume_status")
    public String volumeStatus;

    @Column(name = "runtime_status")
    public String runtimeStatus;

    @Column(name = "active_volume_generation")
    public Long activeVolumeGeneration;

    @Column(name = "size_gb")
    public Integer sizeGb;

    @Column(name = "storage_class")
    public String storageClass;

    @Column(name = "profile")
    public String profile;

    @Column(name = "mount_path")
    public String mountPath;

    @Column(name = "image")
    public String image;

    @Column(name = "runtime_version")
    public String runtimeVersion;

    @Column(name = "env_json")
    public String envJson;

    @Column(name = "bootstrap_script")
    public String bootstrapScript;

    @Column(name = "start_script")
    public String startScript;

    @Column(name = "bootstrap_completed_at")
    public ZonedDateTime bootstrapCompletedAt;

    @Column(name = "bootstrap_script_hash")
    public String bootstrapScriptHash;

    @Column(name = "start_script_hash")
    public String startScriptHash;

    @Column(name = "last_bootstrap_log")
    public String lastBootstrapLog;

    @Column(name = "env_file_present")
    public Boolean envFilePresent;

    @Column(name = "network_enabled")
    public Boolean networkEnabled;

    @Column(name = "network_profile")
    public String networkProfile;

    @Column(name = "idle_sleep_minutes")
    public Integer idleSleepMinutes;

    @Column(name = "runtime_generation")
    public Long runtimeGeneration;

    @Column(name = "last_used_at")
    public ZonedDateTime lastUsedAt;

    @Column(name = "last_provisioned_at")
    public ZonedDateTime lastProvisionedAt;

    @Column(name = "last_woken_at")
    public ZonedDateTime lastWokenAt;

    @Column(name = "last_slept_at")
    public ZonedDateTime lastSleptAt;

    @Column(name = "error_code")
    public String errorCode;

    @Column(name = "error_message")
    public String errorMessage;

    @Column(name = "deleted_at")
    public ZonedDateTime deletedAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
