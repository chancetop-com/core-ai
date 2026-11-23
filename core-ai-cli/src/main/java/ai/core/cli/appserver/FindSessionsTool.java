package ai.core.cli.appserver;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.util.Locale;

/**
 * Desktop-only discovery tool. It returns opaque session references for this workspace only; the
 * host still decides whether the requesting peer may observe a session.
 *
 * @author stephen
 */
final class FindSessionsTool extends ToolCall {
    static final String TOOL_NAME = "find_sessions";

    private static final String TOOL_DESC = """
            List sessions in this workspace so the user can identify one to inspect or watch.
            Use this only when the user asks about progress of another session and the session id is unknown.
            A progress question does not by itself authorize proactive notifications; use watch_session only
            when the user explicitly asks to be told when that task ends.
            """;

    static FindSessionsTool create(EngineSessionRegistry registry) {
        var builder = new Builder();
        builder.name(TOOL_NAME);
        builder.description(TOOL_DESC);
        builder.parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "query",
                        "Optional title or session-id fragment."),
                ToolCallParameters.ParamSpec.of(Integer.class, "limit",
                        "Maximum sessions to return, default 5 and at most 20.")
        ));
        builder.needAuth(Boolean.FALSE);
        builder.directReturn(Boolean.FALSE);
        builder.llmVisible(Boolean.TRUE);
        builder.discoverable(Boolean.FALSE);
        var tool = new FindSessionsTool(registry);
        builder.build(tool);
        return tool;
    }

    private final EngineSessionRegistry registry;

    private FindSessionsTool(EngineSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed(TOOL_NAME + " requires execution context");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var args = parseArguments(arguments);
        var query = getStringValue(args, "query");
        int limit = 5;
        var requested = SessionToolSupport.intValue(args, "limit");
        if (requested != null) limit = Math.max(1, Math.min(requested, 20));
        var params = Params.object();
        params.put("limit", 50);
        var sessions = registry.list(params).path("sessions");
        var builder = new StringBuilder(128 * limit);
        int found = 0;
        for (var session : sessions) {
            if (context != null && context.getSessionId() != null
                    && context.getSessionId().equals(session.path("sessionId").asText())) {
                continue;
            }
            var title = session.path("title").asText("");
            var id = session.path("sessionId").asText("");
            if (query != null && !query.isBlank()) {
                var needle = query.strip().toLowerCase(Locale.ROOT);
                if (!title.toLowerCase(Locale.ROOT).contains(needle) && !id.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
            }
            if (found > 0) builder.append('\n');
            builder.append("session_id: ").append(id)
                    .append("\ntitle: ").append(title)
                    .append("\nstatus: ").append(session.path("status").asText("idle"))
                    .append("\nupdated_at: ").append(session.path("updatedAt").asText("")).append('\n');
            found++;
            if (found >= limit) break;
        }
        if (found == 0) return ToolCallResult.completed("No accessible sessions matched.");
        return ToolCallResult.completed(builder.toString().stripTrailing());
    }

    private static final class Builder extends ToolCall.Builder<Builder, FindSessionsTool> {
        @Override
        protected Builder self() {
            return this;
        }
    }
}
