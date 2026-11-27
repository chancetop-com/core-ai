package ai.core.server.workspace;

/**
 * The PostgreSQL stores a workspace owns. Grouped so the service constructor stays within the
 * checkstyle parameter budget.
 *
 * @author stephen
 */
public record WorkspaceStores(WorkspaceStore workspaces, WorkspaceVolumeStore volumes,
                              WorkspaceBindingStore bindings, WorkspaceEventStore events,
                              WorkspaceLeaseStore leases, WorkspaceUsageStore usage) {
}
