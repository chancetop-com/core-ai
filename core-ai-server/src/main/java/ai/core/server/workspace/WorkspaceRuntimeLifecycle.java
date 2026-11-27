package ai.core.server.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;

/**
 * Stops and inspects the replaceable Sandbox CR behind a workspace. Deleting a runtime never touches
 * the PVC; the binding row is the only pointer that is removed with it.
 *
 * @author stephen
 */
class WorkspaceRuntimeLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceRuntimeLifecycle.class);

    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final WorkspaceRuntimeManager runtimes;

    WorkspaceRuntimeLifecycle(WorkspaceStore workspaces, WorkspaceBindingStore bindings, WorkspaceRuntimeManager runtimes) {
        this.workspaces = workspaces;
        this.bindings = bindings;
        this.runtimes = runtimes;
    }

    boolean runtimeAvailable() {
        return runtimes.available();
    }

    boolean exists(String sandboxId) {
        if (sandboxId == null || sandboxId.isBlank()) return false;
        try {
            return runtimes.get(sandboxId).isPresent();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to read workspace runtime {}, treating it as gone", sandboxId, e);
            return false;
        }
    }

    void stop(WorkspaceRecord workspace) {
        var binding = bindings.get(workspace.id).orElse(null);
        if (binding != null) {
            deleteQuietly(binding.sandboxId);
            bindings.delete(workspace.id);
        }
        if (workspace.runtimeStatus != WorkspaceRuntimeStatus.NONE) {
            workspaces.updateRuntimeState(workspace.id, WorkspaceRuntimeStatus.NONE, workspace.runtimeGeneration, null, null);
            workspaces.touchSlept(workspace.id, ZonedDateTime.now());
        }
    }

    void deleteQuietly(String sandboxName) {
        if (sandboxName == null || sandboxName.isBlank()) return;
        try {
            runtimes.delete(sandboxName);
        } catch (RuntimeException e) {
            // the controller's own shutdown policy will remove it; the volume was never owned by the CR
            LOGGER.warn("failed to delete workspace sandbox {}, leaving it to the controller", sandboxName, e);
        }
    }
}
