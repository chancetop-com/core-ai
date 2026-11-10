package ai.core.server.artifact;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatSession;
import ai.core.server.project.ProjectArtifactBinder;
import ai.core.server.project.ProjectAttributionStore;
import ai.core.server.session.ChatSessionStore;

/**
 * @author xander
 */
public final class ChatSessionArtifactSink implements ArtifactSink {
    private final String sessionId;
    private final ChatSessionStore chatSessionStore;
    private final ProjectArtifactBinder binder;

    public ChatSessionArtifactSink(String sessionId, ChatSessionStore chatSessionStore) {
        this(sessionId, chatSessionStore, null);
    }

    public ChatSessionArtifactSink(String sessionId, ChatSessionStore chatSessionStore, ProjectArtifactBinder binder) {
        this.sessionId = sessionId;
        this.chatSessionStore = chatSessionStore;
        this.binder = binder;
    }

    @Override
    public void append(AgentRunArtifact artifact) {
        if (binder == null) {
            chatSessionStore.appendArtifact(sessionId, artifact);
            return;
        }
        ChatSession session = chatSessionStore.get(sessionId)
            .orElseThrow(() -> new RuntimeException("chat session not found, id=" + sessionId));
        chatSessionStore.appendArtifact(sessionId, artifact);
        // project attribution is inherited from the session once the artifact is persisted (never fails delivery)
        binder.onArtifact(ProjectAttributionStore.TARGET_SESSION, sessionId, artifact.fileId, session.agentId, artifact.createdAt);
    }
}
