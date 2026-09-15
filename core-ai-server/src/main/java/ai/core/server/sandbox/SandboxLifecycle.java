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

    private static final String HUB_MARKER = "# Session capabilities from inside the sandbox";

    private static final String HUB_INSTRUCTIONS = """

            # Session capabilities from inside the sandbox

            Code you run in the sandbox (bash, python, skill scripts) can call everything this session has
            configured — its MCP servers, API tools, LLM_CALL definitions, sub-agents, and non-sandboxed builtin
            tools — through the sandbox hub. Scripts hold no credentials; the platform attaches the session's
            identity to every call.

            - Python: `from core_ai_sandbox import session`, `s = session()`, then `s.mcp["<server>"]["<tool>"](...)`,
              `s.api["<app>"]["<service>"]["<operation>"](...)`, `s.llm_call["<definition>"](query=...)`,
              `s.agent["<name>"].run("...")`, `s.tool("<tool name>")(...)`.
            - bash: `core-ai-sandbox catalog` to discover, `core-ai-sandbox describe <tool>`,
              `core-ai-sandbox call <tool> --args '<json>'`.

            Discover available capabilities with `core-ai-sandbox catalog` / `s.catalog()` instead of guessing
            names, and never hardcode service endpoints or credentials in sandbox scripts. There is no direct
            LLM access: a script reaches an LLM only through an LLM_CALL definition attached to this agent.
            """;

    public static String appendHubInstructions(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) return HUB_INSTRUCTIONS.strip();
        return systemPrompt + HUB_INSTRUCTIONS;
    }

    private static boolean hasSubmitArtifactsTool(Agent agent) {
        return agent.getToolCalls().stream()
                .anyMatch(t -> SubmitArtifactsTool.TOOL_NAME.equals(t.getName()));
    }

    private static boolean hasHubInstructions(Agent agent) {
        var prompt = agent.getSystemPrompt();
        return prompt != null && prompt.contains(HUB_MARKER);
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

        if (!hasHubInstructions(agent)) {
            agent.setSystemPrompt(appendHubInstructions(agent.getSystemPrompt()));
        }

        injectSubmitArtifactsTool(agent, context);
    }

    private void injectSubmitArtifactsTool(Agent agent, ExecutionContext context) {
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
