package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.session.CustomEventEmitter;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;

import java.util.Map;

/**
 * @author stephen
 */
public final class SendCustomEventTool extends ToolCall {
    public static final String TOOL_NAME = "send_custom_event";

    private static final String TOOL_DESC = """
            Send a named custom event to this session's clients (the web UI that renders this conversation, or
            the scripts driving it). The event name and payload shape are defined by the product that renders
            them, so follow the product's own instructions for the exact name and JSON structure. Use it to
            drive rich UI updates that a plain text reply cannot express.

            Keep the payload structured and small: at most 256 KB per event and 1 MB per turn. For large
            content, publish a file and put its URL in the event instead. Events can only be sent while the
            turn is running.
            """;

    public static Builder builder() {
        return new Builder();
    }

    static CustomEventEmitter emitter(ExecutionContext context) {
        if (context == null) return null;
        var value = context.getCustomVariables().get(CustomEventEmitter.CONTEXT_KEY);
        return value instanceof CustomEventEmitter emitter ? emitter : null;
    }

    static String dataJson(Object data) {
        if (data == null) return null;
        if (data instanceof Map<?, ?> map) return JsonUtil.toJson(map);
        if (data instanceof String text) return text.isBlank() ? null : text;
        return null;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var emitter = emitter(context);
        if (emitter == null) {
            return ToolCallResult.failed("this context has no session event channel, so custom events cannot be sent");
        }
        var args = parseArguments(arguments);
        var eventName = getStringValue(args, "event_name");
        if (eventName == null || eventName.isBlank()) {
            return ToolCallResult.failed("event_name is required");
        }
        var dataJson = dataJson(args.get("data"));
        if (dataJson == null) {
            return ToolCallResult.failed("data is required and must be a JSON object");
        }
        var reason = emitter.emit(eventName, dataJson, context.getCurrentToolCallId());
        if (reason != null) {
            return ToolCallResult.failed(reason);
        }
        return ToolCallResult.completed(String.format("Event \"%s\" was sent to the session.", eventName));
    }

    public static class Builder extends ToolCall.Builder<Builder, SendCustomEventTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public SendCustomEventTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "event_name",
                    "The product-defined event name, e.g. \"menu_table\".").required(),
                ToolCallParameters.ParamSpec.of(Map.class, "data",
                    "A JSON object carrying the event payload; its shape is defined by the product that renders the event.").required()
            ));
            this.needAuth(Boolean.FALSE);
            this.directReturn(Boolean.FALSE);
            this.llmVisible(Boolean.TRUE);
            this.discoverable(Boolean.FALSE);
            var tool = new SendCustomEventTool();
            build(tool);
            return tool;
        }
    }
}
