package ai.core.server.workflow.executor;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.StagedFile;
import ai.core.server.workflow.ArtifactStaging;
import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import ai.core.server.workflow.VariablePool;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * CODE node: run the node's Python in a stateless sandbox. Inputs are declared as {@code name -> selector} in
 * the config, resolved over the variable pool and injected as an {@code inputs} dict the script reads; data only
 * flows through the pool, never the sandbox FS (per the Dify model). A fresh sandbox is created and closed per
 * run; provider (k8s/docker) is chosen by the platform.
 *
 * <p>File flow (design §5.3.2): artifacts an input selector references are staged by the platform into the
 * sandbox at their deterministic path before the script runs, and the resolved artifact objects carry that
 * {@code path} — the script reads the file locally, no network access required. Staged inputs are part of the
 * invocation (like the inputs dict), so the stateless contract is unchanged.
 *
 * <p>Output contract: if the script assigns a {@code result} variable, its JSON form is the node output and any
 * stdout before it is debug-only (the appended epilogue prints it behind a sentinel line this executor splits
 * on). With no {@code result}, the whole stdout is the output — the original contract, fully backward compatible.
 *
 * <p>Config shape (authored on the canvas): {@code {"code": "result = inputs['x']", "inputs": {"x": "nodes.start.output.v"}}}.
 *
 * @author Xander
 */
public class CodeExecutor implements NodeExecutor {
    private static final String PYTHON_TOOL = "run_python_script";
    static final String RESULT_SENTINEL = "__WORKFLOW_CODE_NODE_RESULT__";
    // The \\n inside the text block reaches Python as the two-char escape, so print emits a real leading
    // newline — the sentinel stays on its own line even after unterminated sys.stdout.write output.
    private static final String RESULT_EPILOGUE = """

        if 'result' in globals():
            print('\\n%s')
            print(json.dumps(result, ensure_ascii=False, default=str))
        """.formatted(RESULT_SENTINEL);

    // Union of the staging sets of every input selector (a selector referencing artifacts whole / by index /
    // by .path stages the file; metadata-only references don't — same rule as AGENT templates).
    private static List<StagedFile> stagedInputFiles(NodeContext ctx) {
        Object inputs = ctx.node().config().get("inputs");
        if (!(inputs instanceof Map<?, ?> map)) {
            return List.of();
        }
        var seen = new LinkedHashSet<String>();
        var staged = new ArrayList<StagedFile>();
        for (Object value : map.values()) {
            if (value instanceof String selector) {
                for (StagedFile file : ArtifactStaging.scanSelector(selector, ctx.pool())) {
                    if (seen.add(file.targetPath())) {
                        staged.add(file);
                    }
                }
            }
        }
        return staged;
    }

    /** A completed run's output: the {@code result} JSON after the sentinel when the script assigned one,
     *  otherwise the whole stdout. Anything else is a deterministic failure. */
    static NodeOutcome toOutcome(ToolCallResult result) {
        String raw = result.getResult() == null ? "" : result.getResult();
        if (!result.isCompleted()) {
            return new NodeOutcome.Fail(raw.strip(), false);
        }
        int sentinel = raw.lastIndexOf(RESULT_SENTINEL);
        String output = sentinel >= 0 ? raw.substring(sentinel + RESULT_SENTINEL.length()) : raw;
        return new NodeOutcome.Normal(output.strip());
    }

    /** Always prepend an {@code inputs} dict the script can read (empty -> {}), so user code can safely reference
     *  {@code inputs} even when no inputs are mapped; append the {@code result} epilogue (see class doc).
     *  The inputs JSON is base64-encoded before embedding: its alphabet ([A-Za-z0-9+/=]) cannot contain a quote
     *  or backslash, so an upstream value with {@code '''} or a trailing {@code \} can never break out of the
     *  string literal and be executed as Python (data-to-code injection). */
    static String buildScript(String code, Map<String, Object> inputs) {
        String encoded = Base64.getEncoder().encodeToString(JSON.toJSON(inputs).getBytes(StandardCharsets.UTF_8));
        // chdir into a fresh temp dir per execution: CODE nodes share one per-run container, so this keeps
        // parallel nodes' relative-path file writes from clashing in a shared cwd.
        return "import json, base64, os, tempfile\nos.chdir(tempfile.mkdtemp())\n"
            + "inputs = json.loads(base64.b64decode('" + encoded + "').decode('utf-8'))\n"
            + code + RESULT_EPILOGUE;
    }

    private static Map<String, Object> resolveInputs(NodeContext ctx, VariablePool pool) {
        Object inputs = ctx.node().config().get("inputs");
        if (!(inputs instanceof Map<?, ?> map)) {
            return Map.of();
        }
        var resolved = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() instanceof String selector) {
                Object value = pool.resolve(selector).orElse(null);
                if (value != null) {
                    resolved.put(String.valueOf(entry.getKey()), coerce(value));
                }
            }
        }
        return resolved;
    }

    // A whole-output selector resolves to a raw JSON string; parse it so the script reads a real object/array
    // instead of a quoted string. Field selectors already resolve to typed values and pass through unchanged.
    static Object coerce(Object value) {
        if (!(value instanceof String string)) {
            return value;
        }
        String trimmed = string.strip();
        try {
            if (trimmed.startsWith("{")) {
                return JSON.fromJSON(Map.class, trimmed);
            }
            if (trimmed.startsWith("[")) {
                return JSON.fromJSON(List.class, trimmed);
            }
        } catch (RuntimeException ignored) {
            // not valid JSON after all — fall through to the raw string
        }
        return value;
    }

    private final SandboxService sandboxService;
    private final FileService fileService;

    public CodeExecutor(SandboxService sandboxService, FileService fileService) {
        this.sandboxService = sandboxService;
        this.fileService = fileService;
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        Object codeValue = ctx.node().config().get("code");
        if (!(codeValue instanceof String code) || code.isBlank()) {
            return new NodeOutcome.Fail("CODE node '" + ctx.node().id() + "' has no code", false);
        }
        if (sandboxService == null) {
            return new NodeOutcome.Fail("sandbox is not configured (set sys.sandbox.provider)", false);
        }
        List<StagedFile> stagedFiles = stagedInputFiles(ctx);
        String script = buildScript(code, resolveInputs(ctx, stagedFiles.isEmpty() ? ctx.pool() : ctx.pool().stagedView()));
        // One sandbox per workflow RUN, shared by every CODE node (created lazily on first use, reused thereafter,
        // and released by WorkflowRunner when the run ends). Amortizes container cold-start. Each script still
        // reads inputs / writes result via the variable pool — the shared container is a cost optimization, NOT a
        // data channel: scripts must not rely on files persisting across nodes (recovery/handoff loses them).
        // Staged input files are the one sanctioned FS input: deterministic per-node paths, written before the run.
        String sessionId = "wf-code:" + ctx.run().id;
        Sandbox sandbox = sandboxService.getOrCreateSandbox(SandboxService.createDefaultConfig(), sessionId, ctx.run().userId);
        if (sandbox == null) {
            return new NodeOutcome.Fail("sandbox is disabled", false);
        }
        for (StagedFile file : stagedFiles) {
            try {
                sandbox.uploadFile(file.targetPath(), fileService.getBytes(fileService.get(file.fileId())));
            } catch (RuntimeException e) {
                // deterministic, retryable: failing fast beats running the script against a missing input file
                return new NodeOutcome.Fail("failed to stage input file " + file.fileName() + ": " + e.getMessage(), true);
            }
        }
        var exec = ExecutionContext.builder().sessionId(sessionId).userId(ctx.run().userId).sandbox(sandbox).build();
        ToolCallResult result = sandbox.execute(PYTHON_TOOL, JSON.toJSON(Map.of("code", script)), exec);
        return toOutcome(result);
    }
}
