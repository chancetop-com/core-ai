package ai.core.server.workspace;

/**
 * Runtime lifecycle of the replaceable sandbox that mounts the workspace volume.
 *
 * @author stephen
 */
public enum WorkspaceRuntimeStatus {
    // no runtime handle exists (never started, or deleted after sleep)
    NONE,
    PROVISIONING,
    READY,
    SLEEPING,
    // the volume is fine; the runtime failed to schedule/mount/start and can be retried
    ERROR
}
