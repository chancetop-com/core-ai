package ai.core.server.workspace;

import java.util.concurrent.Executor;

/**
 * Package-private collaborator bundle shared between {@link WorkspaceService} and archive/restore
 * services without bloating {@link WorkspaceService} with individual accessors.
 *
 * @author stephen
 */
record WorkspaceArchiveAccess(WorkspaceStores stores, WorkspaceVolumeManager volumeManager,
                              WorkspaceRuntimeManager runtimes, WorkspaceRuntimeLifecycle runtimeLifecycle,
                              WorkspaceRuntimeStarter runtimeStarter, WorkspaceLeaseManager leases,
                              WorkspaceAudit audit, WorkspaceCreator creator, WorkspaceConfig config,
                              Executor wakeExecutor) {
}
