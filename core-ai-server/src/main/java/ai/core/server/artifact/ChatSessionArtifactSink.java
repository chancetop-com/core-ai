package ai.core.server.artifact;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatSession;
import ai.core.server.project.ProjectArtifactBinder;
import ai.core.server.project.ProjectAttributionStore;
import core.framework.mongo.MongoCollection;

import java.util.ArrayList;

/**
 * @author xander
 */
public final class ChatSessionArtifactSink implements ArtifactSink {
    private final String sessionId;
    private final MongoCollection<ChatSession> chatSessionCollection;
    private final ProjectArtifactBinder binder;

    public ChatSessionArtifactSink(String sessionId, MongoCollection<ChatSession> chatSessionCollection) {
        this(sessionId, chatSessionCollection, null);
    }

    public ChatSessionArtifactSink(String sessionId, MongoCollection<ChatSession> chatSessionCollection, ProjectArtifactBinder binder) {
        this.sessionId = sessionId;
        this.chatSessionCollection = chatSessionCollection;
        this.binder = binder;
    }

    @Override
    public void append(AgentRunArtifact artifact) {
        var session = chatSessionCollection.get(sessionId)
            .orElseThrow(() -> new RuntimeException("chat session not found, id=" + sessionId));
        var artifacts = session.artifacts != null ? new ArrayList<>(session.artifacts) : new ArrayList<AgentRunArtifact>();
        artifacts.add(artifact);
        session.artifacts = artifacts;
        chatSessionCollection.replace(session);
        // project attribution is inherited from the session once the artifact is persisted (never fails delivery)
        if (binder != null) binder.onArtifact(ProjectAttributionStore.TARGET_SESSION, sessionId, artifact.fileId);
    }
}
