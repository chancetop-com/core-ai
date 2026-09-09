package ai.core.server.artifact;

import ai.core.server.domain.ChatSession;
import ai.core.server.file.FileService;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

/**
 * @author xander
 */
public class ChatArtifactSetup {
    @Inject
    FileService fileService;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    ai.core.server.project.ProjectArtifactBinder artifactBinder;

    public ArtifactSink createChatSessionSink(String sessionId) {
        return new ChatSessionArtifactSink(sessionId, chatSessionCollection, artifactBinder);
    }
}
