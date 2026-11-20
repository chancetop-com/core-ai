package ai.core.cli.appserver;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

/**
 * Read-only Task Run projection of another session. It does not resume a turn, start a model call or
 * claim execution ownership; it only opens the persisted session when needed to read its journal.
 *
 * @author stephen
 */
final class InspectSessionTool extends ToolCall {
    static final String TOOL_NAME = "inspect_session";

    private static final String TOOL_DESC = """
            Show the current Task Run of another session in this workspace.
            Use it to answer "how is that task going" without starting or steering the source session.
            A progress check does not create a notification; use watch_session only after an explicit request.
            """;

    static InspectSessionTool create(EngineSessionRegistry registry) {
        var builder = new Builder();
        builder.name(TOOL_NAME);
        builder.description(TOOL_DESC);
        builder.parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "session_id",
                        "Target session id in this workspace.").required()
        ));
        builder.needAuth(Boolean.FALSE);
        builder.directReturn(Boolean.FALSE);
        builder.llmVisible(Boolean.TRUE);
        builder.discoverable(Boolean.FALSE);
        var tool = new InspectSessionTool(registry);
        builder.build(tool);
        return tool;
    }

    private final EngineSessionRegistry registry;

    private InspectSessionTool(EngineSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed(TOOL_NAME + " requires execution context");
    }

    @Override
    public ToolCallResult execute(String arguments, ai.core.agent.ExecutionContext context) {
        var args = parseArguments(arguments);
        var sessionId = getStringValue(args, "session_id");
        if (sessionId == null || sessionId.isBlank()) return ToolCallResult.failed("session_id is required");
        try {
            var target = SessionToolSupport.requireSession(registry, sessionId.strip());
            return ToolCallResult.completed(SessionToolSupport.text(target.lifecycle().inspect()));
        } catch (RuntimeException e) {
            return ToolCallResult.failed(SessionToolSupport.errorText(e));
        }
    }

    private static final class Builder extends ToolCall.Builder<Builder, InspectSessionTool> {
        @Override
        protected Builder self() {
            return this;
        }
    }
}
