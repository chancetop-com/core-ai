package ai.core.server.sandbox;

import ai.core.api.server.session.SandboxEvent;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.server.session.ChatSessionStore;
import ai.core.server.workspace.WorkspaceService;

import java.util.function.Consumer;

/**
 * Creates the workspace-backed sandbox for a session that was stamped with a workspace at creation.
 * Sessions without a workspace keep the existing snapshot-restored sandbox.
 *
 * @author stephen
 */
public class WorkspaceSandboxFactory {
    private final WorkspaceService workspaces;
    private final ChatSessionStore sessions;
    private final SandboxProvider provider;

    public WorkspaceSandboxFactory(WorkspaceService workspaces, ChatSessionStore sessions, SandboxProvider provider) {
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.provider = provider;
    }

    public Sandbox create(SandboxConfig config, String sessionId, String userId, Consumer<SandboxEvent> eventDispatcher,
                          Runnable postAttach) {
        if (!workspaces.enabled() || provider == null) return null;
        var session = sessions.get(sessionId).orElse(null);
        if (session == null || session.workspaceId == null) return null;
        var owner = userId != null ? userId : session.userId;
        if (session.userId != null && !session.userId.equals(owner)) return null;
        return new WorkspaceSandbox(config, new WorkspaceSandbox.SessionRef(sessionId, owner, session.workspaceId),
                workspaces, provider, eventDispatcher, postAttach);
    }
}
