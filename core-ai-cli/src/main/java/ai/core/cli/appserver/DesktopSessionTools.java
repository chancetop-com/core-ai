package ai.core.cli.appserver;

import ai.core.agent.Agent;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.RoleType;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GetVideoStatusTool;
import ai.core.tool.tools.PresentChoicesTool;
import ai.core.tool.tools.SendCustomEventTool;

import java.nio.file.Path;
import java.util.List;

/**
 * Wiring that only a desktop (app-server) session gets: artifact delivery into the panel, and the
 * session-event channel behind rich cards and quick replies. The interactive CLI mounts neither — it
 * has no artifact panel and no session event emitter.
 *
 * @author stephen
 */
final class DesktopSessionTools {

    static void install(Path workspace, Agent agent) {
        var context = agent.getExecutionContext();
        var artifactSink = new LocalArtifactSink(workspace, context);
        context.getCustomVariables().put(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY, artifactSink);
        context.getCustomVariables().put(GetVideoStatusTool.VIDEO_OUTPUT_SINK_CONTEXT_KEY, artifactSink);
        agent.addTools(List.of(
                LocalSubmitArtifactsTool.create(workspace),
                SendCustomEventTool.builder().build(),
                PresentChoicesTool.builder().build()));
        appendArtifactInstructions(agent);
    }

    /**
     * Desktop deliverable flow: non-media files enter the artifact panel only when the agent registers
     * them through submit_artifacts (media files are captured by the sink automatically). The
     * instructions go into the literal prompt appendix and, for a resumed session, into the already-sent
     * messages[0] — the copy the model actually reads (same reason as MemorySectionManager).
     */
    private static void appendArtifactInstructions(Agent agent) {
        var prompt = agent.getSystemPromptAppendix();
        if (prompt != null && prompt.contains(LocalSubmitArtifactsTool.PROMPT_MARKER)) {
            return;
        }
        agent.setSystemPromptAppendix(LocalSubmitArtifactsTool.appendInstructions(prompt));
        var messages = agent.getMessages();
        if (messages.isEmpty()) {
            return;
        }
        var first = messages.getFirst();
        if (first.role != RoleType.SYSTEM || first.content == null || first.content.isEmpty()) {
            return;
        }
        var text = first.content.getFirst().text;
        if (text == null || text.contains(LocalSubmitArtifactsTool.PROMPT_MARKER)) {
            return;
        }
        first.content = List.of(Content.of(LocalSubmitArtifactsTool.appendInstructions(text)));
    }

    private DesktopSessionTools() {
    }
}
