package ai.core.api.server.workspace;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class WorkspaceView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "agent_id")
    public String agentId;

    @NotNull
    @Property(name = "is_default")
    public Boolean isDefault;

    @NotNull
    @Property(name = "volume_status")
    public String volumeStatus;

    @NotNull
    @Property(name = "runtime_status")
    public String runtimeStatus;

    @Property(name = "active_volume_generation")
    public Long activeVolumeGeneration;

    @NotNull
    @Property(name = "runtime_generation")
    public Long runtimeGeneration;

    @NotNull
    @Property(name = "size_gb")
    public Integer sizeGb;

    @Property(name = "used_bytes")
    public Long usedBytes;

    @Property(name = "capacity_bytes")
    public Long capacityBytes;

    @NotNull
    @Property(name = "storage_class")
    public String storageClass;

    @NotNull
    @Property(name = "profile")
    public String profile;

    @NotNull
    @Property(name = "mount_path")
    public String mountPath;

    @NotNull
    @Property(name = "network_enabled")
    public Boolean networkEnabled;

    @Property(name = "network_profile")
    public String networkProfile;

    @NotNull
    @Property(name = "idle_sleep_minutes")
    public Integer idleSleepMinutes;

    @Property(name = "last_used_at")
    public ZonedDateTime lastUsedAt;

    @Property(name = "last_provisioned_at")
    public ZonedDateTime lastProvisionedAt;

    @Property(name = "error_code")
    public String errorCode;

    @Property(name = "error_message")
    public String errorMessage;

    @Property(name = "deleted_at")
    public ZonedDateTime deletedAt;

    @NotNull
    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
