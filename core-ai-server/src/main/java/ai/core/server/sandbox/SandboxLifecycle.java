package ai.core.server.sandbox;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.Node;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.server.artifact.ArtifactSink;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.file.FileService;
import ai.core.server.run.SubmitArtifactsTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author lim chen
 */
public final class SandboxLifecycle extends AbstractLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxLifecycle.class);

    private static boolean hasSubmitArtifactsTool(Agent agent) {
        return agent.getToolCalls().stream()
                .anyMatch(t -> SubmitArtifactsTool.TOOL_NAME.equals(t.getName()));
    }

    private final FileService fileService;
    private final ArtifactSink artifactSink;
    private final PublicUrlConfiguration publicUrlConfiguration;

    public SandboxLifecycle(FileService fileService, ArtifactSink artifactSink,
                            PublicUrlConfiguration publicUrlConfiguration) {
        this.fileService = fileService;
        this.artifactSink = artifactSink;
        this.publicUrlConfiguration = publicUrlConfiguration;
    }

    @Override
    public void beforeAgentRun(Node<?> node, AtomicReference<String> query,
                                ExecutionContext context) {
        if (!(node instanceof Agent agent)) return;

        var sandbox = context.getSandbox();
        if (sandbox == null) return;

        if (hasSubmitArtifactsTool(agent)) return;

        var userId = context.getUserId();
        if (userId == null || userId.isBlank()) {
            LOGGER.warn("SandboxLifecycle: userId is null, skip, sessionId={}", context.getSessionId());
            return;
        }

        var tool = SubmitArtifactsTool.create(userId, fileService, artifactSink, publicUrlConfiguration);
        agent.addTools(List.of(tool));

        agent.setSystemPrompt(SubmitArtifactsTool.appendInstructions(agent.getSystemPrompt()));

        LOGGER.debug("SandboxLifecycle: injected sandbox tools/instructions, sessionId={}", context.getSessionId());
    }
}
