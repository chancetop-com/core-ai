package ai.core.server.workspace;

/**
 * Deployment knobs for the workspace feature, resolved once from {@code sys.workspace.*} so the
 * service and the API layer read the same numbers.
 *
 * @author stephen
 */
public class WorkspaceConfig {
    public boolean enabled;
    public String storageClass = WorkspaceDefaults.STORAGE_CLASS;
    public String image = WorkspaceDefaults.IMAGE;
    public String provider = KubernetesWorkspaceProvisioner.PROVIDER;
    public int defaultSizeGb = WorkspaceDefaults.SIZE_GB;
    public int maxSizeGb = WorkspaceDefaults.MAX_SIZE_GB;
    public int maxWorkspacesPerUser = WorkspaceDefaults.MAX_WORKSPACES_PER_USER;
    public int maxTotalGbPerUser = WorkspaceDefaults.MAX_TOTAL_GB_PER_USER;
    public int maxRunningPerUser = WorkspaceDefaults.MAX_RUNNING_PER_USER;
    public int idleSleepMinutes = WorkspaceDefaults.IDLE_SLEEP_MINUTES;
    public int wakeTimeoutSeconds = WorkspaceDefaults.WAKE_TIMEOUT_SECONDS;
    public int leaseTtlSeconds = WorkspaceDefaults.LEASE_TTL_SECONDS;
    public int leaseWaitSeconds = WorkspaceDefaults.LEASE_WAIT_SECONDS;
    public int minRuntimeSeconds = WorkspaceDefaults.MIN_RUNTIME_SECONDS;
    public int purgeAfterDays = WorkspaceDefaults.PURGE_AFTER_DAYS;
}
