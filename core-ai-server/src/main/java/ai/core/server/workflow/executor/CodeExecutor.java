package ai.core.server.workflow.executor;

import ai.core.agent.ExecutionContext;
import ai.core.sandbox.Sandbox;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CODE node: run the node's Python in a stateless sandbox and surface stdout as the node output. Inputs are
 * declared as {@code name -> selector} in the config, resolved over the variable pool and injected as an
 * {@code inputs} dict the script reads; data only flows through the pool, never the sandbox FS (per the Dify
 * model). A fresh sandbox is created and closed per run; provider (k8s/docker) is chosen by the platform.
 *
 * <p>Config shape (authored on the canvas): {@code {"code": "print(inputs['x'])", "inputs": {"x": "nodes.start.output.v"}}}.
 *
 * @author Xander
 */
public class CodeExecutor implements NodeExecutor {
    private static final String PYTHON_TOOL = "run_python_script";

    private final SandboxService sandboxService;

    public CodeExecutor(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
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
        String script = buildScript(code, resolveInputs(ctx));
        String sessionId = "wf-code:" + ctx.run().id + ':' + ctx.node().id();
        Sandbox sandbox = sandboxService.createSandbox(SandboxService.createDefaultConfig(), sessionId, ctx.run().userId);
        try {
            var exec = ExecutionContext.builder().sessionId(sessionId).userId(ctx.run().userId).sandbox(sandbox).build();
            ToolCallResult result = sandbox.execute(PYTHON_TOOL, JSON.toJSON(Map.of("code", script)), exec);
            return toOutcome(result);
        } finally {
            close(sandbox);
        }
    }

    /** A completed run's stdout is the node output; anything else is a deterministic failure. */
    static NodeOutcome toOutcome(ToolCallResult result) {
        String output = result.getResult() == null ? "" : result.getResult().strip();
        return result.isCompleted() ? new NodeOutcome.Normal(output) : new NodeOutcome.Fail(output, false);
    }

    /** Prepend an {@code inputs} dict the script can read; no inputs means the code runs unchanged. */
    static String buildScript(String code, Map<String, Object> inputs) {
        if (inputs.isEmpty()) {
            return code;
        }
        return "import json\ninputs = json.loads(r'''" + JSON.toJSON(inputs) + "''')\n" + code;
    }

    private static Map<String, Object> resolveInputs(NodeContext ctx) {
        Object inputs = ctx.node().config().get("inputs");
        if (!(inputs instanceof Map<?, ?> map)) {
            return Map.of();
        }
        var resolved = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() instanceof String selector) {
                Object value = ctx.pool().resolve(selector).orElse(null);
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
        } catch (RuntimeException e) {
            // not valid JSON after all — fall through to the raw string
        }
        return value;
    }

    private static void close(Sandbox sandbox) {
        try {
            sandbox.close();
        } catch (RuntimeException e) {
            // best-effort release; the provider also reclaims the sandbox on its idle timeout
        }
    }
}
