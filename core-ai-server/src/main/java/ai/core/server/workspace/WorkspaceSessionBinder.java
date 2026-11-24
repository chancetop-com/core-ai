package ai.core.server.workspace;

import ai.core.server.domain.ChatSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stamps a new session with the default workspace of (user, agent). Workspace problems never block
 * session creation: the session falls back to the ephemeral sandbox and the workspace API reports
 * the failure separately.
 *
 * @author stephen
 */
public class WorkspaceSessionBinder {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceSessionBinder.class);

    private final WorkspaceService workspaces;

    public WorkspaceSessionBinder(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    public void bindNewSession(ChatSession session) {
        if (session == null || session.userId == null || session.agentId == null || session.agentId.isBlank()) return;
        if (!workspaces.enabled()) return;
        try {
            var workspace = workspaces.resolveDefault(session.userId, session.agentId);
            session.workspaceId = workspace.id;
            session.workspaceGeneration = workspace.activeVolumeGeneration;
        } catch (RuntimeException e) {
            LOGGER.warn("failed to resolve the default workspace, session={}, agent={}",
                    session.id, session.agentId, e);
        }
    }
}
