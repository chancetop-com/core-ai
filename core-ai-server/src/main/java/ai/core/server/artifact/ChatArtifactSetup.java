package ai.core.server.artifact;

import ai.core.server.file.FileService;
import ai.core.server.session.ChatSessionStore;
import core.framework.inject.Inject;

/**
 * @author xander
 */
public class ChatArtifactSetup {
    @Inject
    FileService fileService;
    @Inject
    ChatSessionStore chatSessionStore;
    @Inject
    ai.core.server.project.ProjectArtifactBinder artifactBinder;

    public ArtifactSink createChatSessionSink(String sessionId) {
        return new ChatSessionArtifactSink(sessionId, chatSessionStore, artifactBinder);
    }
}
