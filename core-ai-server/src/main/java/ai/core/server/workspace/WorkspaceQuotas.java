package ai.core.server.workspace;

import java.util.List;
import java.util.Objects;

/**
 * Workspace quota checks, kept off the service so its lifecycle methods stay readable.
 *
 * @author stephen
 */
final class WorkspaceQuotas {
    static void checkCreate(WorkspaceConfig config, List<WorkspaceRecord> owned, int sizeGb) {
        if (owned.size() >= config.maxWorkspacesPerUser) {
            throw new WorkspaceException(WorkspaceException.QUOTA_EXCEEDED,
                    "workspace limit reached (" + config.maxWorkspacesPerUser + ")");
        }
        var totalGb = owned.stream().mapToInt(w -> w.sizeGb).sum() + sizeGb;
        if (totalGb > config.maxTotalGbPerUser) {
            throw new WorkspaceException(WorkspaceException.QUOTA_EXCEEDED,
                    "total workspace storage limit reached (" + config.maxTotalGbPerUser + " GB)");
        }
    }

    static void checkRunning(WorkspaceConfig config, List<WorkspaceRecord> owned, String excludeId) {
        var running = owned.stream()
                .filter(w -> !Objects.equals(w.id, excludeId))
                .filter(w -> w.runtimeStatus == WorkspaceRuntimeStatus.READY || w.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING)
                .count();
        if (running >= config.maxRunningPerUser) {
            throw new WorkspaceException(WorkspaceException.QUOTA_EXCEEDED,
                    "running workspace limit reached (" + config.maxRunningPerUser + ")");
        }
    }

    private WorkspaceQuotas() {
    }
}
