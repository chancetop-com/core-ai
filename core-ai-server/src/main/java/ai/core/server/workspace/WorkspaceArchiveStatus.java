package ai.core.server.workspace;

/**
 * Lifecycle state of a workspace cold archive stored in object storage.
 *
 * @author stephen
 */
public enum WorkspaceArchiveStatus {
    CREATING,
    READY,
    FAILED,
    DELETED
}
