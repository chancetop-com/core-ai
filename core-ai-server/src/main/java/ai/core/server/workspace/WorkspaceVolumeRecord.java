package ai.core.server.workspace;

import java.time.ZonedDateTime;

/**
 * One row per workspace generation. This row, not the workspace record, is authoritative for
 * volume status, PVC name, size and bind location.
 *
 * @author stephen
 */
public class WorkspaceVolumeRecord {
    public static WorkspaceVolumeRecord provisioning(WorkspaceRecord workspace, long generation, String pvcName, String provider) {
        var now = ZonedDateTime.now();
        var volume = new WorkspaceVolumeRecord();
        volume.workspaceId = workspace.id;
        volume.volumeGeneration = generation;
        volume.pvcName = pvcName;
        volume.provider = provider;
        volume.sizeGb = workspace.sizeGb;
        volume.storageClass = workspace.storageClass;
        volume.status = WorkspaceVolumeStatus.PROVISIONING;
        volume.createdAt = now;
        volume.updatedAt = now;
        return volume;
    }

    public String workspaceId;
    public long volumeGeneration;
    public String pvcName;
    public String provider;
    public int sizeGb;
    public String storageClass;
    public WorkspaceVolumeStatus status;
    public String nodeName;
    public String zone;
    public ZonedDateTime boundAt;
    public ZonedDateTime deletedAt;
    public ZonedDateTime createdAt;
    public ZonedDateTime updatedAt;

}
