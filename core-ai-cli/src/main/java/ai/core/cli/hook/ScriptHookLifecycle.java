package ai.core.cli.hook;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.cli.plugin.PluginManager;
import ai.core.llm.domain.FunctionCall;
import ai.core.tool.ToolCallResult;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class ScriptHookLifecycle extends AbstractLifecycle {

    private static String toolName(FunctionCall functionCall) {
        return functionCall.function != null ? functionCall.function.name : "";
    }

    /**
     * Convenience method to fire SessionStop hooks for the given workspace.
     * Encapsulates PluginManager + HookConfig loading so callers don't
     * need to duplicate it at every session-close point.
     */
    public static void fireSessionStopHooks(Path workspace) {
        var rootDir = Path.of(System.getProperty("user.home"), ".core-ai");
        var hookConfig = HookConfig.load(workspace, PluginManager.getInstance(rootDir));
        if (!hookConfig.isEmpty()) {
            new ScriptHookLifecycle(hookConfig, new ScriptHookRunner(workspace)).runSessionStopHooks();
        }
    }

    private final HookConfig config;
    private final ScriptHookRunner runner;

    public ScriptHookLifecycle(HookConfig config, ScriptHookRunner runner) {
        this.config = config;
        this.runner = runner;
    }

    public String runSessionStartHooks() {
        var hooks = config.getHooks(HookEvent.SESSION_START);
        if (hooks.isEmpty()) return "";

        var sb = new StringBuilder();
        for (var hook : hooks) {
            String output = runner.run(hook.command(), Collections.emptyMap());
            if (!output.isEmpty()) {
                sb.append(output).append('\n');
            }
        }
        return sb.toString().strip();
    }

    public void runSessionStopHooks() {
        var hooks = config.getHooks(HookEvent.SESSION_STOP);
        if (hooks.isEmpty()) return;

        for (var hook : hooks) {
            runner.run(hook.command(), Collections.emptyMap());
        }
    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {
        var hooks = config.getHooks(HookEvent.USER_PROMPT_SUBMIT);
        if (hooks.isEmpty()) return;

        var env = Map.of("CORE_AI_USER_QUERY", query.get() != null ? query.get() : "");
        var sb = new StringBuilder();
        for (var hook : hooks) {
            String output = runner.run(hook.command(), env);
            if (!output.isEmpty()) {
                sb.append(output).append('\n');
            }
        }
        if (!sb.isEmpty()) {
            query.set(query.get() + "\n\n" + sb.toString().strip());
        }
    }

    @Override
    public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {
        var hooks = config.getHooks(HookEvent.PRE_TOOL_USE);
        if (hooks.isEmpty()) return;

        String toolName = toolName(functionCall);
        var env = Map.of(
                "CORE_AI_TOOL_NAME", toolName,
                "CORE_AI_TOOL_ARGUMENTS", functionCall.function != null ? functionCall.function.arguments : "");

        for (var hook : hooks) {
            if (hook.matches(toolName)) {
                runner.run(hook.command(), env);
            }
        }
    }

    @Override
    public void afterTool(FunctionCall functionCall, ExecutionContext executionContext, ToolCallResult toolResult) {
        var hooks = config.getHooks(HookEvent.POST_TOOL_USE);
        if (hooks.isEmpty()) return;

        String toolName = toolName(functionCall);
        String toolOutput = toolResult != null && toolResult.getResult() != null ? toolResult.getResult() : "";
        var env = Map.of(
                "CORE_AI_TOOL_NAME", toolName,
                "CORE_AI_TOOL_OUTPUT", toolOutput);

        for (var hook : hooks) {
            if (!hook.matches(toolName)) continue;
            String output = runner.run(hook.command(), env);
            if (!output.isEmpty() && toolResult != null) {
                String current = toolResult.getResult();
                toolResult.withResult((current != null ? current : "") + "\n" + output);
            }
        }
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext executionContext) {
        var hooks = config.getHooks(HookEvent.AFTER_AGENT_RUN);
        if (hooks.isEmpty()) return;

        var env = Map.of(
                "CORE_AI_QUERY", query != null ? query : "",
                "CORE_AI_RESULT", result.get() != null ? result.get() : "");

        for (var hook : hooks) {
            runner.run(hook.command(), env);
        }
    }

}
