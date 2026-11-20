package ai.core.cli.appserver;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

/**
 * Cancels only the notification promise. It never stops the source task or another owner's watch.
 *
 * @author stephen
 */
final class CancelSessionWatchTool extends ToolCall {
    static final String TOOL_NAME = "cancel_session_watch";

    private static final String TOOL_DESC = """
            Cancel a watch created by this conversation. This stops the future notification only;
            it does not cancel or change the watched task. Sending may already have started, in which
            case the host will report that the message cannot be recalled.
            """;

    static CancelSessionWatchTool create(EngineSessionRegistry registry) {
        var builder = new Builder();
        builder.name(TOOL_NAME);
        builder.description(TOOL_DESC);
        builder.parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "watch_id", "Watch id returned by watch_session.").required()
        ));
        builder.needAuth(Boolean.FALSE);
        builder.directReturn(Boolean.FALSE);
        builder.llmVisible(Boolean.TRUE);
        builder.discoverable(Boolean.FALSE);
        var tool = new CancelSessionWatchTool(registry);
        builder.build(tool);
        return tool;
    }

    private final EngineSessionRegistry registry;

    private CancelSessionWatchTool(EngineSessionRegistry registry) {
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
        var watchId = getStringValue(args, "watch_id");
        if (watchId == null || watchId.isBlank()) return ToolCallResult.failed("watch_id is required");
        var payload = Params.object();
        payload.put("watchId", watchId.strip());
        try {
            var result = registry.hostRequests().request("cancel_session_watch",
                    SessionToolSupport.hostContext(context, registry), payload, HostRequestBridge.DEFAULT_TIMEOUT_MILLIS);
            return ToolCallResult.completed(SessionToolSupport.text(result));
        } catch (RuntimeException e) {
            return ToolCallResult.failed(SessionToolSupport.errorText(e));
        }
    }

    private static final class Builder extends ToolCall.Builder<Builder, CancelSessionWatchTool> {
        @Override
        protected Builder self() {
            return this;
        }
    }
}
