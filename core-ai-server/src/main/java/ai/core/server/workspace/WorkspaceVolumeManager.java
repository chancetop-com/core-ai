package ai.core.server.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Volume lifecycle of a workspace: make the active generation usable, replace it on reset, and keep the
 * workspace row's volume cache in sync. PVCs are owned here, never by a Sandbox.
 *
 * @author stephen
 */
class WorkspaceVolumeManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceVolumeManager.class);

    private final WorkspaceVolumeStore volumes;
    private final WorkspaceStore workspaces;
    private final WorkspaceProvisioner provisioner;

    WorkspaceVolumeManager(WorkspaceVolumeStore volumes, WorkspaceStore workspaces, WorkspaceProvisioner provisioner) {
        this.volumes = volumes;
        this.workspaces = workspaces;
        this.provisioner = provisioner;
    }

    WorkspaceVolumeRecord ensure(WorkspaceRecord workspace) {
        var volume = volumes.active(workspace.id).orElse(null);
        if (volume == null) {
            var generation = workspace.activeVolumeGeneration != null ? workspace.activeVolumeGeneration : 0L;
            var created = WorkspaceVolumeRecord.provisioning(workspace, generation,
                    provisioner.volumeName(workspace.id, generation), provisioner.provider());
            if (volumes.insertIfAbsent(created)) {
                volume = created;
            } else {
                volume = volumes.active(workspace.id)
                        .orElseThrow(() -> new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE,
                                "workspace volume is already being provisioned elsewhere"));
            }
        }
        try {
            provisioner.ensureVolume(workspace, volume.volumeGeneration);
            volumes.updateStatus(workspace.id, volume.volumeGeneration, WorkspaceVolumeStatus.READY);
            workspaces.updateVolumeState(workspace.id, WorkspaceVolumeStatus.READY, volume.volumeGeneration, null, null);
            volume.status = WorkspaceVolumeStatus.READY;
            return volume;
        } catch (WorkspaceProvisioningException e) {
            volumes.updateStatus(workspace.id, volume.volumeGeneration, WorkspaceVolumeStatus.ERROR);
            workspaces.updateVolumeState(workspace.id, WorkspaceVolumeStatus.ERROR, volume.volumeGeneration,
                    e.errorCode, e.getMessage());
            throw e;
        }
    }

    /**
     * Replaces the durable environment; the caller has already stopped the runtime.
     */
    long reset(WorkspaceRecord workspace) {
        var id = workspace.id;
        var oldGeneration = workspace.activeVolumeGeneration;
        if (oldGeneration != null) {
            var oldVolume = volumes.get(id, oldGeneration).orElse(null);
            if (oldVolume != null) {
                volumes.markDeleted(id, oldGeneration, WorkspaceVolumeStatus.DELETING);
                try {
                    provisioner.deleteVolume(oldVolume);
                    volumes.markDeleted(id, oldGeneration, WorkspaceVolumeStatus.DELETED);
                } catch (WorkspaceProvisioningException e) {
                    throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE,
                            "cannot delete the previous workspace volume, reset aborted", e);
                }
            }
        }

        var generation = oldGeneration == null ? 0 : oldGeneration + 1;
        var volume = WorkspaceVolumeRecord.provisioning(workspace, generation,
                provisioner.volumeName(id, generation), provisioner.provider());
        if (!volumes.insertIfAbsent(volume)) {
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE,
                    "workspace volume generation already exists: " + generation);
        }
        try {
            provisioner.ensureVolume(workspace, generation);
            volumes.updateStatus(id, generation, WorkspaceVolumeStatus.READY);
            workspaces.updateVolumeState(id, WorkspaceVolumeStatus.READY, generation, null, null);
            return generation;
        } catch (WorkspaceProvisioningException e) {
            volumes.updateStatus(id, generation, WorkspaceVolumeStatus.ERROR);
            workspaces.updateVolumeState(id, WorkspaceVolumeStatus.ERROR, generation, e.errorCode, e.getMessage());
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE, e.getMessage(), e);
        }
    }

    WorkspaceVolumeRecord active(String workspaceId) {
        return volumes.active(workspaceId).orElse(null);
    }

    List<WorkspaceVolumeRecord> list(String workspaceId) {
        return volumes.listByWorkspace(workspaceId);
    }

    List<WorkspaceVolumeRecord> listByStatus(WorkspaceVolumeStatus status) {
        return volumes.listByStatus(status);
    }

    void markDeleting(WorkspaceVolumeRecord volume) {
        volumes.markDeleted(volume.workspaceId, volume.volumeGeneration, WorkspaceVolumeStatus.DELETING);
        volume.status = WorkspaceVolumeStatus.DELETING;
    }

    void restoreStatus(WorkspaceVolumeRecord volume, WorkspaceVolumeStatus status) {
        volumes.updateStatus(volume.workspaceId, volume.volumeGeneration, status);
        volume.status = status;
    }

    /** @return true when the PVC is gone and the row is marked DELETED; false leaves it for the next sweep */
    boolean deleteNow(WorkspaceVolumeRecord volume) {
        try {
            provisioner.deleteVolume(volume);
            volumes.markDeleted(volume.workspaceId, volume.volumeGeneration, WorkspaceVolumeStatus.DELETED);
            volume.status = WorkspaceVolumeStatus.DELETED;
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to delete workspace volume, retry next sweep, workspace={}, pvc={}",
                    volume.workspaceId, volume.pvcName, e);
            return false;
        }
    }
}
