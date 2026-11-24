package ai.core.server.workspace;

/**
 * Volume lifecycle. AUTHORITATIVE state lives on {@code app.workspace_volumes}; the copy on
 * {@link WorkspaceRecord} is a cache repaired from the volume row.
 *
 * @author stephen
 */
public enum WorkspaceVolumeStatus {
    // PVC object created (or being created); with WaitForFirstConsumer it is still Pending here,
    // which means "usable for a mount", not an error.
    PROVISIONING,
    READY,
    DELETING,
    DELETED,
    ERROR
}
