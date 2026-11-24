package ai.core.server.sandbox;

import ai.core.api.server.session.SandboxEvent;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;

import java.util.function.Consumer;

/**
 * Chooses the backend for a session sandbox: a workspace-backed handle when the session was stamped with
 * a workspace, otherwise the snapshot-restored lazy sandbox.
 *
 * @author stephen
 */
final class SessionSandboxBuilder {
    private final SandboxManager manager;
    private volatile WorkspaceSandboxFactory workspaceFactory;

    SessionSandboxBuilder(SandboxManager manager) {
        this.manager = manager;
    }

    void workspaceFactory(WorkspaceSandboxFactory factory) {
        this.workspaceFactory = factory;
    }

    Sandbox create(SandboxConfig config, String sessionId, String userId, Consumer<SandboxEvent> dispatcher,
                   SandboxSnapshotService snapshot, Consumer<SandboxSnapshotService.RestoreResult> onReady) {
        var factory = workspaceFactory;
        if (factory != null) {
            var workspace = factory.create(config, sessionId, userId, dispatcher,
                    () -> onReady.accept(new SandboxSnapshotService.RestoreResult(SandboxSnapshotService.RestoreOutcome.NONE, null)));
            if (workspace != null) return workspace;
        }
        return new LazySandbox(config, manager, dispatcher, new LazySandbox.SessionIdentity(sessionId, userId),
                onReady, snapshot);
    }
}
