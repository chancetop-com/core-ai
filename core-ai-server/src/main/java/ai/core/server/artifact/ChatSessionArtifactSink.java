package ai.core.server.artifact;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatSession;
import core.framework.mongo.MongoCollection;

import java.util.ArrayList;

/**
 * @author xander
 */
public final class ChatSessionArtifactSink implements ArtifactSink {
    private final String sessionId;
    private final MongoCollection<ChatSession> chatSessionCollection;

    public ChatSessionArtifactSink(String sessionId, MongoCollection<ChatSession> chatSessionCollection) {
        this.sessionId = sessionId;
        this.chatSessionCollection = chatSessionCollection;
    }

    @Override
    public void append(AgentRunArtifact artifact) {
        var session = chatSessionCollection.get(sessionId)
            .orElseThrow(() -> new RuntimeException("chat session not found, id=" + sessionId));
        var artifacts = session.artifacts != null ? new ArrayList<>(session.artifacts) : new ArrayList<AgentRunArtifact>();
        artifacts.add(artifact);
        session.artifacts = artifacts;
        chatSessionCollection.replace(session);
    }
}
