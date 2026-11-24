package ai.core.server.workspace;

/**
 * Product defaults for the persistent workspace feature. Deployment-level overrides land in
 * {@code sys.workspace.*} properties; values here are the safe fallbacks used when a Property is
 * absent and the documented numbers the UI labels.
 *
 * @author stephen
 */
public final class WorkspaceDefaults {
    public static final int SIZE_GB = 32;
    public static final int MAX_SIZE_GB = 100;
    public static final int MAX_WORKSPACES_PER_USER = 3;
    public static final int MAX_TOTAL_GB_PER_USER = 96;
    public static final int MAX_RUNNING_PER_USER = 2;
    public static final int IDLE_SLEEP_MINUTES = 15;
    public static final int MIN_RUNTIME_SECONDS = 300;
    public static final int MAX_RUNTIME_SECONDS = 8 * 3600;
    public static final int PROVISION_TIMEOUT_SECONDS = 300;
    public static final int WAKE_TIMEOUT_SECONDS = 180;
    public static final int BOOTSTRAP_TIMEOUT_SECONDS = 1800;
    public static final int START_TIMEOUT_SECONDS = 600;
    public static final int PURGE_AFTER_DAYS = 7;
    public static final String STORAGE_CLASS = "managed-csi";
    public static final String MOUNT_PATH = "/workspace";
    public static final String NETWORK_PROFILE = "public-egress";
    public static final String IMAGE = "ghcr.io/chancetop/core-ai-sandbox:latest";

    private WorkspaceDefaults() {
    }
}
