package ai.core.cli.appserver;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.util.UUID;

/**
 * Desktop-only tool: hands a user message to another session in the same workspace. The target is
 * resumed when needed, and the message is queued behind any running turn instead of steering into
 * it. The forwarded turn keeps its own turn id and events, so the caller can correlate later.
 *
 * @author stephen
 */
final class SendSessionMessageTool extends ToolCall {
    static final String TOOL_NAME = "send_session_message";

    private static final String TOOL_DESC = """
            Forward a user message to another session in this workspace and return immediately.

            Use this when the user explicitly asks to continue or send something to a session by id,
            for example "continue session cli-20261030-164801" or "tell that session to run the tests".
            The target session is resumed from disk when it is not live. The message is queued behind
            any running turn and becomes the target session's next user turn; it is not injected into
            the middle of that turn.

            Do not use this for the current session (reply normally) and do not use it to reach a
            session in another workspace. The target session's answer appears in that session; this
            tool does not wait for it.
            """;

    static SendSessionMessageTool create(Forwarder forwarder) {
        var builder = new Builder();
        builder.name(TOOL_NAME);
        builder.description(TOOL_DESC);
        builder.parameters(ToolCallParameters.of(
                ToolCallParameters.ParamSpec.of(String.class, "session_id",
                        "Target session id in this workspace, e.g. \"cli-20261030-164801\".").required(),
                ToolCallParameters.ParamSpec.of(String.class, "message",
                        "The user message to forward verbatim.").required()
        ));
        builder.needAuth(Boolean.FALSE);
        builder.directReturn(Boolean.FALSE);
        builder.llmVisible(Boolean.TRUE);
        builder.discoverable(Boolean.FALSE);
        var tool = new SendSessionMessageTool(forwarder);
        builder.build(tool);
        return tool;
    }

    private static String errorText(RpcException e) {
        var data = e.data();
        var code = data == null ? null : data.path("code").asText(null);
        return (code == null ? "error" : code) + ": " + e.getMessage();
    }

    private final Forwarder forwarder;

    private SendSessionMessageTool(Forwarder forwarder) {
        this.forwarder = forwarder;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed(TOOL_NAME + " requires execution context");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        if (context == null) return ToolCallResult.failed(TOOL_NAME + " requires execution context");
        var args = parseArguments(arguments);
        var sessionId = getStringValue(args, "session_id");
        if (sessionId == null || sessionId.isBlank()) return ToolCallResult.failed("session_id is required");
        sessionId = sessionId.strip();
        if (sessionId.equals(context.getSessionId())) {
            return ToolCallResult.failed("session_id must differ from the current session; answer normally instead");
        }
        var message = getStringValue(args, "message");
        if (message == null || message.isBlank()) return ToolCallResult.failed("message is required");

        var turnId = UUID.randomUUID().toString();
        try {
            var forwarded = forwarder.forward(sessionId, message, turnId);
            return ToolCallResult.completed(String.format(
                    "Message forwarded to session %s.%nstatus: %s%nqueued: %s%nturn_id: %s",
                    forwarded.sessionId(), forwarded.status(), forwarded.queued(), forwarded.turnId()));
        } catch (RpcException e) {
            return ToolCallResult.failed(errorText(e));
        } catch (RuntimeException e) {
            return ToolCallResult.failed(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    interface Forwarder {
        Forwarded forward(String sessionId, String message, String turnId);
    }

    record Forwarded(String sessionId, String status, boolean queued, String turnId) {
    }

    private static final class Builder extends ToolCall.Builder<Builder, SendSessionMessageTool> {
        @Override
        protected Builder self() {
            return this;
        }
    }
}
