package ai.core.server.workspace;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Durable workspace record. {@code volumeStatus} / {@code activeVolumeGeneration} are derived
 * caches of {@link WorkspaceVolumeRecord}; {@code runtimeStatus} is authoritative here.
 *
 * @author stephen
 */
public class WorkspaceRecord {
    public static WorkspaceRecord create(String ownerUserId, String name, String agentId) {
        var now = ZonedDateTime.now();
        var workspace = new WorkspaceRecord();
        workspace.id = UUID.randomUUID().toString();
        workspace.ownerUserId = ownerUserId;
        workspace.name = name;
        workspace.agentId = agentId;
        workspace.volumeStatus = WorkspaceVolumeStatus.PROVISIONING;
        workspace.runtimeStatus = WorkspaceRuntimeStatus.NONE;
        workspace.sizeGb = WorkspaceDefaults.SIZE_GB;
        workspace.storageClass = WorkspaceDefaults.STORAGE_CLASS;
        workspace.profile = WorkspaceProfile.DEFAULT;
        workspace.mountPath = WorkspaceDefaults.MOUNT_PATH;
        workspace.image = WorkspaceDefaults.IMAGE;
        workspace.networkEnabled = true;
        workspace.networkProfile = WorkspaceDefaults.NETWORK_PROFILE;
        workspace.idleSleepMinutes = WorkspaceDefaults.IDLE_SLEEP_MINUTES;
        workspace.createdAt = now;
        workspace.updatedAt = now;
        return workspace;
    }

    public String id;
    public String ownerUserId;
    public String name;
    // default agent binding; may be null for a manually created workspace
    public String agentId;
    public boolean isDefault;
    public WorkspaceVolumeStatus volumeStatus;
    public WorkspaceRuntimeStatus runtimeStatus;
    public Long activeVolumeGeneration;
    public int sizeGb;
    public String storageClass;
    public WorkspaceProfile profile;
    public String mountPath;
    public String image;
    public String runtimeVersion;
    public Map<String, String> env = new LinkedHashMap<>();
    public String bootstrapScript;
    public String startScript;
    public ZonedDateTime bootstrapCompletedAt;
    public String bootstrapScriptHash;
    public String startScriptHash;
    public String lastBootstrapLog;
    public boolean envFilePresent;
    public boolean networkEnabled;
    public String networkProfile;
    public int idleSleepMinutes;
    public long runtimeGeneration;
    public ZonedDateTime lastUsedAt;
    public ZonedDateTime lastProvisionedAt;
    public ZonedDateTime lastWokenAt;
    public ZonedDateTime lastSleptAt;
    public String errorCode;
    public String errorMessage;
    public ZonedDateTime deletedAt;
    public ZonedDateTime createdAt;
    public ZonedDateTime updatedAt;

    public boolean deleted() {
        return deletedAt != null;
    }
}
