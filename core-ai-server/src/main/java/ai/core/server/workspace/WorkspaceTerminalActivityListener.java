package ai.core.server.workspace;

import ai.core.server.session.ChatSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.function.Consumer;

/**
 * Keeps a session's bound persistent workspace awake on interactive terminal activity without
 * acquiring a turn lease. Failures are isolated so terminal heartbeats never break.
 *
 * @author stephen
 */
public class WorkspaceTerminalActivityListener implements Consumer<String> {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceTerminalActivityListener.class);

    private final WorkspaceConfig config;
    private final WorkspaceStore workspaces;
    private final ChatSessionStore sessions;

    public WorkspaceTerminalActivityListener(WorkspaceConfig config, WorkspaceStore workspaces, ChatSessionStore sessions) {
        this.config = config;
        this.workspaces = workspaces;
        this.sessions = sessions;
    }

    @Override
    public void accept(String sessionId) {
        if (config == null || !config.enabled || workspaces == null || sessions == null
                || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            var session = sessions.get(sessionId).orElse(null);
            if (session == null || session.workspaceId == null || session.workspaceId.isBlank()) return;
            var workspace = workspaces.get(session.workspaceId).orElse(null);
            if (workspace == null || workspace.deleted()) return;
            workspaces.touchLastUsed(workspace.id, ZonedDateTime.now());
        } catch (RuntimeException e) {
            LOGGER.warn("failed to touch workspace activity for terminal session, session={}", sessionId, e);
        }
    }
}
