package ai.core.cli.appserver;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Registers one durable interest in the final end of a specific Task Run. The host owns the origin
 * address and the outbound promise; this tool only asks for that registration and reports what the
 * host actually wrote.
 *
 * @author stephen
 */
final class WatchSessionTool extends ToolCall {
    static final String TOOL_NAME = "watch_session";

    private static final String TOOL_DESC = """
            Ask the system to notify the user on the original channel when a specific task run ends.
            Call this only when the user explicitly asks to be notified about the end, for example
            "tell me when that task finishes". A simple "how is it going" does not authorize a watch.
            The watch covers one task run, including its required background work and result processing;
            it is not a subscription to every future turn in that session.
            """;

    static WatchSessionTool create(EngineSessionRegistry registry) {
        var builder = new Builder();
        builder.name(TOOL_NAME);
        builder.description(TOOL_DESC);
        builder.parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "session_id",
                        "Target session id in this workspace.").required(),
                ToolCallParameters.ParamSpec.of(String.class, "scope",
                        "current_task_run (default) or explicit_task_run."),
                ToolCallParameters.ParamSpec.of(String.class, "task_run_ref",
                        "Required for explicit_task_run: taskRunId or taskRunId#generation from inspect_session."),
                ToolCallParameters.ParamSpec.of(Integer.class, "expires_in_seconds",
                        "Optional watch lifetime, 60 seconds to 7 days.")
        ));
        builder.needAuth(Boolean.FALSE);
        builder.directReturn(Boolean.FALSE);
        builder.llmVisible(Boolean.TRUE);
        builder.discoverable(Boolean.FALSE);
        var tool = new WatchSessionTool(registry);
        builder.build(tool);
        return tool;
    }

    private static void addExpiry(ObjectNode payload, Map<String, Object> args) {
        var seconds = SessionToolSupport.intValue(args, "expires_in_seconds");
        if (seconds != null) payload.put("expiresInSeconds", Math.max(60, Math.min(seconds, 7 * 24 * 60 * 60)));
    }

    private final EngineSessionRegistry registry;

    private WatchSessionTool(EngineSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed(TOOL_NAME + " requires execution context");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
        var sessionId = getStringValue(args, "session_id");
        if (sessionId == null || sessionId.isBlank()) return ToolCallResult.failed("session_id is required");
        sessionId = sessionId.strip();
        if (context == null) return ToolCallResult.failed(TOOL_NAME + " requires execution context");
        if (sessionId.equals(context.getSessionId())) {
            return ToolCallResult.failed("SELF_WATCH: a channel agent cannot watch its own current task run");
        }
        var scope = getStringValue(args, "scope");
        if (scope == null || scope.isBlank()) scope = "current_task_run";
        if (!List.of("current_task_run", "explicit_task_run").contains(scope)) {
            return ToolCallResult.failed("scope must be current_task_run or explicit_task_run");
        }
        try {
            var target = SessionToolSupport.requireSession(registry, sessionId);
            return "explicit_task_run".equals(scope)
                    ? watchExplicit(target, sessionId, scope, args, context)
                    : watchCurrent(target, sessionId, scope, args, context);
        } catch (RuntimeException e) {
            return ToolCallResult.failed(SessionToolSupport.errorText(e));
        }
    }

    private ToolCallResult watchCurrent(EngineSession target, String sessionId, String scope,
                                        Map<String, Object> args, ExecutionContext context) {
        var run = target.inProcess().taskRuns().activeSnapshot();
        if (run == null || run.terminal()) {
            var node = Params.object();
            node.put("error", "NO_ACTIVE_TASK_RUN");
            node.put("sessionId", sessionId);
            var last = target.lifecycle().inspect().path("lastEndedTaskRun");
            if (!last.isMissingNode()) node.set("lastEndedTaskRun", last);
            return ToolCallResult.failed(node.toPrettyString());
        }
        if (!"complete".equals(run.coverage())) {
            return ToolCallResult.failed("TASK_RUN_UNTRACKABLE: required work adapter is not wired (" + run.coverage() + ")");
        }
        return request(payload(sessionId, run.taskRunId(), run.runGeneration(), scope, args), context);
    }

    private ToolCallResult watchExplicit(EngineSession target, String sessionId, String scope,
                                         Map<String, Object> args, ExecutionContext context) {
        var ref = SessionToolSupport.parseRunRef(getStringValue(args, "task_run_ref"));
        if (ref == null) return ToolCallResult.failed("task_run_ref is required for explicit_task_run");
        var active = target.inProcess().taskRuns().activeSnapshot();
        if (active != null && ref.runGeneration() == 0 && ref.taskRunId().equals(active.taskRunId())) {
            ref = new SessionToolSupport.RunRef(active.taskRunId(), active.runGeneration());
        }
        if (ref.runGeneration() == 0) {
            var ended = endedRef(target);
            if (ended != null && ref.taskRunId().equals(ended.taskRunId())) ref = ended;
        }
        if (ref.runGeneration() == 0) {
            return ToolCallResult.failed("task_run_ref must include the generation, e.g. run-1#3");
        }
        if (active != null && ref.taskRunId().equals(active.taskRunId())
                && ref.runGeneration() == active.runGeneration() && !"complete".equals(active.coverage())) {
            return ToolCallResult.failed("TASK_RUN_UNTRACKABLE: required work adapter is not wired (" + active.coverage() + ")");
        }
        return request(payload(sessionId, ref.taskRunId(), ref.runGeneration(), scope, args), context);
    }

    private ObjectNode payload(String sessionId, String taskRunId, long runGeneration, String scope, Map<String, Object> args) {
        var payload = Params.object();
        payload.put("sourceSessionId", sessionId);
        payload.put("sourceTaskRunId", taskRunId);
        payload.put("runGeneration", runGeneration);
        payload.put("scope", scope);
        payload.put("sourceAlias", registry.sessionTitle(sessionId));
        addExpiry(payload, args);
        return payload;
    }

    private ToolCallResult request(ObjectNode payload, ExecutionContext context) {
        var hostRequests = registry.hostRequests();
        if (hostRequests == null) return ToolCallResult.failed("desktop host is not attached");
        var result = hostRequests.request("watch_session", SessionToolSupport.hostContext(context, registry),
                payload, HostRequestBridge.DEFAULT_TIMEOUT_MILLIS);
        if (result.has("error")) {
            return ToolCallResult.failed(result.path("error").asText("watch registration failed"));
        }
        return ToolCallResult.completed(SessionToolSupport.text(result));
    }

    private SessionToolSupport.RunRef endedRef(EngineSession target) {
        var ended = target.lifecycle().inspect().path("lastEndedTaskRun");
        if (ended.isMissingNode()) return null;
        var run = ended.path("run");
        var taskRunId = run.path("taskRunId").asText(null);
        var generation = run.path("runGeneration").asLong(0);
        if (taskRunId == null || generation == 0) return null;
        return new SessionToolSupport.RunRef(taskRunId, generation);
    }

    private static final class Builder extends ToolCall.Builder<Builder, WatchSessionTool> {
        @Override
        protected Builder self() {
            return this;
        }
    }
}
