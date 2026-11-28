package ai.core.server.workspace;

/**
 * Provider-neutral contract for the durable volume behind a workspace. Implementations must be
 * idempotent: a retry after a crash must adopt the existing volume, never create a second one.
 *
 * @author stephen
 */
public interface WorkspaceProvisioner {
    String provider();

    String volumeName(String workspaceId, long volumeGeneration);

    /** creates the volume when absent, validates it when present; never waits for a mount */
    VolumeHandle ensureVolume(WorkspaceRecord workspace, long volumeGeneration);

    /** idempotent: a missing volume is success */
    void deleteVolume(WorkspaceVolumeRecord volume);

    default boolean volumeExists(WorkspaceVolumeRecord volume) {
        return volume != null && volume.status == WorkspaceVolumeStatus.READY;
    }

    class VolumeHandle {
        public final String volumeName;
        // provider phase as reported; with WaitForFirstConsumer a Pending PVC is ready to mount
        public final String phase;

        public VolumeHandle(String volumeName, String phase) {
            this.volumeName = volumeName;
            this.phase = phase;
        }
    }
}
