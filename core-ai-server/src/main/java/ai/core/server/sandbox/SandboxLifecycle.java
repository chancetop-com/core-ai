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
            tools — through the sandbox hub. Scripts hold no credentials: the platform attaches the session's
            identity to every call, and there is no direct LLM access (a script reaches an LLM only through an
            LLM_CALL definition or a sub-agent attached to this agent).

            Discover what is attached before writing calls; never guess tool names:

            - Python: `s.catalog()`, `s.tools(query="pod", kind="mcp")`, `s.describe("<name|ref_id|path>")` to
              see a tool's `input_schema` and `timeout_seconds`.
            - Bash: `core-ai-sandbox catalog`, `core-ai-sandbox tools <query> [--kind K]`,
              `core-ai-sandbox describe <tool>`.

            Call a tool with the arguments its `input_schema` declares, passed as keyword arguments. Every
            namespace accepts item and attribute access, and `-`/`_` are interchangeable in every name:

            ```python
            from core_ai_sandbox import session
            from core_ai_sandbox.errors import ToolError

            s = session()
            try:
                pods = s.mcp["kubernetes"]["pods_list_in_namespace"](namespace="dev-ai")  # <server>/<tool>
                print(pods.data or pods.text)        # .data = .text parsed as JSON, else None
            except ToolError as error:               # a failed call raises, it never returns
                print("failed:", error)              # error.tool / .status_code / .task_id

            page = s.api["<app>"]["<service>"]["<operation>"](...)   # <app>/<service>/<operation>
            answer = s.llm_call["<definition>"](query="...")         # .text is the model's answer
            review = s.agent["<name>"].run("...")                    # sub-agent, the SDK waits for it
            url = s.files.publish("report.html", title="Weekly")     # sandbox file -> download_url
            ```

            - `ToolResult`: `.text` (raw payload), `.data` (text parsed as JSON, else None), `.content`,
              `.duration_ms`, `.llm_usage`. Print `.data` or a short summary, not whole payloads.
            - Failures raise `ToolError` (`.tool`, `.error_code`, `.status_code`, `.task_id`);
              `ToolNotFoundError` when the name is not attached; `TypeError` when an argument is missing or has
              the wrong type. Catch `ToolError` when one forbidden or broken item should become data instead of
              aborting the script, and treat a permission error as final for that capability rather than
              probing alternatives.
            - Slow tools: `wait=False` returns a `Task` to poll (`task.poll()`, `task.result`), and a call still
              pending after `wait_timeout` raises a `ToolError` carrying its `task_id`. `timeout=`, `wait=` and
              `wait_timeout=` belong to the SDK unless the tool's `input_schema` declares them, so
              `run_bash(timeout=5000)` stays the sandbox bash timeout.
            - `s.files.publish(path)` makes a script's output file a session artifact and returns its
              `download_url`, for tools that need to fetch a file the script produced.

            From bash, `core-ai-sandbox call <tool> --arg k=v` (repeatable, values coerced from the
            input_schema) or `--args '<json>'`; `--json`, `--raw`, `--quiet` and `--timeout N` shape the output.
            The exit code is the result — 0 ok, 1 tool error, 2 usage, 3 not bound, 4 forbidden, 5 not found,
            6 timeout (the task may still be running) — so branch on it instead of scraping text.

            This reference covers the whole hub surface: use it instead of reading the installed SDK source,
            keep endpoints and credentials out of scripts, and prefer one script that prints a compact result
            over many one-off calls.
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
