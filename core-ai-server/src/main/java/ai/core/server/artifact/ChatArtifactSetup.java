package ai.core.server.artifact;

import ai.core.api.server.session.SessionConfig;
import ai.core.server.domain.ChatSession;
import ai.core.server.file.FileService;
import ai.core.server.run.SubmitArtifactsTool;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.ArrayList;
import java.util.List;

/**
 * @author xander
 */
public class ChatArtifactSetup {
    @Inject
    FileService fileService;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;

    public List<ToolCall> withSubmitArtifactsTool(List<ToolCall> baseTools, String sessionId, String userId, boolean sandboxEnabled) {
        if (!sandboxEnabled) return baseTools;
        var tool = SubmitArtifactsTool.create(userId, fileService, new ChatSessionArtifactSink(sessionId, chatSessionCollection));
        var combined = new ArrayList<ToolCall>();
        if (baseTools != null && !baseTools.isEmpty()) combined.addAll(baseTools);
        else combined.addAll(BuiltinTools.ALL);
        combined.add(tool);
        return combined;
    }

    public SessionConfig appendArtifactInstructions(SessionConfig config, boolean sandboxEnabled) {
        if (!sandboxEnabled) return config;
        var effective = config != null ? config : new SessionConfig();
        effective.systemPrompt = SubmitArtifactsTool.appendInstructions(effective.systemPrompt);
        return effective;
    }
}
