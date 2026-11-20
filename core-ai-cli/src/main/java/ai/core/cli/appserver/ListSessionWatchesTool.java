package ai.core.cli.appserver;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

/**
 * Lists the watches owned by the current origin. The answer comes from the host's durable store, not
 * from the model's memory of an earlier turn.
 *
 * @author stephen
 */
final class ListSessionWatchesTool extends ToolCall {
    static final String TOOL_NAME = "list_session_watches";

    private static final String TOOL_DESC = """
            List the watch registrations created for this conversation's original channel identity,
            including whether each one has been matched and whether its notification was accepted.
            Use it when the user asks what is being watched or whether a promised notification was sent.
            """;

    static ListSessionWatchesTool create(EngineSessionRegistry registry) {
        var builder = new Builder();
        builder.name(TOOL_NAME);
        builder.description(TOOL_DESC);
        builder.parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "watch_id", "Optional single watch id."),
                ToolCallParameters.ParamSpec.of(Integer.class, "limit", "Maximum watches, default 20 and at most 50.")
        ));
        builder.needAuth(Boolean.FALSE);
        builder.directReturn(Boolean.FALSE);
        builder.llmVisible(Boolean.TRUE);
        builder.discoverable(Boolean.FALSE);
        var tool = new ListSessionWatchesTool(registry);
        builder.build(tool);
        return tool;
    }

    private final EngineSessionRegistry registry;

    private ListSessionWatchesTool(EngineSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed(TOOL_NAME + " requires execution context");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        if (context == null) return ToolCallResult.failed(TOOL_NAME + " requires execution context");
        var args = parseArguments(arguments);
        var payload = Params.object();
        var watchId = getStringValue(args, "watch_id");
        if (watchId != null && !watchId.isBlank()) payload.put("watchId", watchId.strip());
        var limit = SessionToolSupport.intValue(args, "limit");
        payload.put("limit", limit == null ? 20 : Math.max(1, Math.min(limit, 50)));
        try {
            var result = registry.hostRequests().request("list_session_watches",
                    SessionToolSupport.hostContext(context, registry), payload, HostRequestBridge.DEFAULT_TIMEOUT_MILLIS);
            return ToolCallResult.completed(SessionToolSupport.text(result));
        } catch (RuntimeException e) {
            return ToolCallResult.failed(SessionToolSupport.errorText(e));
        }
    }

    private static final class Builder extends ToolCall.Builder<Builder, ListSessionWatchesTool> {
        @Override
        protected Builder self() {
            return this;
        }
    }
}
