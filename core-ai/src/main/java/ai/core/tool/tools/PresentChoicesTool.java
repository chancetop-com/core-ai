package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Presents interactive options to the user as the platform-standard {@code quick_replies} custom event.
 * Clicking an option sends its value back as the next user message; the tool itself is non-blocking.
 *
 * @author stephen
 */
public final class PresentChoicesTool extends ToolCall {
    public static final String TOOL_NAME = "present_choices";
    public static final String QUICK_REPLIES_EVENT = "quick_replies";

    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 6;
    private static final String INVALID_OPTION = "each option must be a JSON object with a non-blank \"label\"";

    private static final String TOOL_DESC = """
            Present the user with interactive choice buttons (2-6 options) when the conversation reaches a point
            where the user should pick between clear alternatives. The choices render as quick-reply buttons on the
            client; clicking one sends its value back as the next user message.

            This is a non-blocking broadcast: the turn continues normally and does not wait for the click, so keep
            your reply text complete on its own. Use present_choices only when the product's flow calls for
            offering choices.
            """;

    public static Builder builder() {
        return new Builder();
    }

    private static String optionFailure(List<?> items) {
        if (items.size() < MIN_OPTIONS || items.size() > MAX_OPTIONS) {
            return "options must contain " + MIN_OPTIONS + " to " + MAX_OPTIONS + " items";
        }
        return null;
    }

    private static Map<String, Object> option(Object item) {
        if (!(item instanceof Map<?, ?> raw)) return null;
        var label = text(raw.get("label"));
        if (label == null || label.isBlank()) return null;
        var option = new LinkedHashMap<String, Object>();
        option.put("label", label);
        var value = text(raw.get("value"));
        option.put("value", value == null || value.isBlank() ? label : value);
        var description = text(raw.get("description"));
        if (description != null && !description.isBlank()) {
            option.put("description", description);
        }
        return option;
    }

    private static String text(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        return JsonUtil.toJson(value);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var emitter = SendCustomEventTool.emitter(context);
        if (emitter == null) {
            return ToolCallResult.failed("this context has no session event channel, so choices cannot be presented");
        }
        var args = parseArguments(arguments);
        if (!(args.get("options") instanceof List<?> items)) {
            return ToolCallResult.failed("options is required and must be an array of 2 to 6 items");
        }
        var countFailure = optionFailure(items);
        if (countFailure != null) return ToolCallResult.failed(countFailure);
        var options = new ArrayList<Map<String, Object>>();
        for (var item : items) {
            var option = option(item);
            if (option == null) return ToolCallResult.failed(INVALID_OPTION);
            options.add(option);
        }
        var payload = new LinkedHashMap<String, Object>();
        var question = getStringValue(args, "question");
        if (question != null && !question.isBlank()) {
            payload.put("question", question);
        }
        payload.put("options", options);
        var reason = emitter.emit(QUICK_REPLIES_EVENT, JsonUtil.toJson(payload), context.getCurrentToolCallId());
        if (reason != null) {
            return ToolCallResult.failed(reason);
        }
        return ToolCallResult.completed("The choices were presented to the user.");
    }

    public static class Builder extends ToolCall.Builder<Builder, PresentChoicesTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public PresentChoicesTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "question",
                    "Optional short question shown above the choices, e.g. \"Would you like to continue?\".").optional(),
                ToolCallParameters.ParamSpec.of(List.class, "options", """
                    Array of 2-6 option objects. Each item MUST be a JSON object with a required `label` (the button
                    text). Optional fields: value (the message sent when clicked; defaults to label), description
                    (a secondary line under the label).
                    Correct example: [{"label":"Yes"},{"label":"No","value":"no thanks","description":"skip this step"}]
                    """).required()
            ));
            this.needAuth(Boolean.FALSE);
            this.directReturn(Boolean.FALSE);
            this.llmVisible(Boolean.TRUE);
            this.discoverable(Boolean.FALSE);
            var tool = new PresentChoicesTool();
            build(tool);
            return tool;
        }
    }
}
