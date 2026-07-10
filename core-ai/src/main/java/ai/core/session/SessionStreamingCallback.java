package ai.core.session;

import ai.core.agent.ExecutionContext;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.EnvironmentOutputChunkEvent;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.OnToolEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.TextChunkEvent;

import ai.core.llm.domain.FunctionCall;
import core.framework.util.Strings;

import java.util.List;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class SessionStreamingCallback implements StreamingCallback {

    private final String sessionId;
    private final Consumer<AgentEvent> dispatcher;
    private final ExecutionContext context;

    public SessionStreamingCallback(String sessionId, Consumer<AgentEvent> dispatcher, ExecutionContext context) {
        this.sessionId = sessionId;
        this.dispatcher = dispatcher;
        this.context = context;
    }

    @Override
    public void onChunk(String chunk) {
        dispatcher.accept(TextChunkEvent.of(sessionId, chunk));
    }

    @Override
    public void onReasoningChunk(String chunk) {
        dispatcher.accept(ReasoningChunkEvent.of(sessionId, chunk));
    }

    @Override
    public void onReasoningComplete(String reasoning) {
        dispatcher.accept(ReasoningCompleteEvent.of(sessionId, reasoning));
    }

    @Override
    public void onTool(List<FunctionCall> functionCalls) {
        for (FunctionCall call : functionCalls) {
            if (call == null || Strings.isBlank(call.id)) continue;
            dispatcher.accept(OnToolEvent.of(sessionId, call.function.name, call.function.arguments));
        }
    }

    @Override
    public void onOutput(String source, String callId, String chunk) {
        dispatcher.accept(EnvironmentOutputChunkEvent.of(sessionId, source, callId, chunk));
    }

    @Override
    public void onError(Throwable error) {
        dispatcher.accept(ErrorEvent.of(sessionId, error.getMessage(), ""));
    }

    @Override
    public void setActiveConnection(AutoCloseable connection) {
        var token = context.getCancellationToken();
        if (token != null) token.bindResource(connection);
    }

    @Override
    public void cancelConnection() {
        // Cancel the entire token tree — this is intentionally broader than the old
        // targeted connection-close. Agent.cancel() no longer calls this method; it
        // calls token.cancel() directly. This method exists for StreamingCallback
        // interface compatibility and any external callers that need to trigger a
        // full cancellation via the callback.
        var token = context.getCancellationToken();
        if (token != null) token.cancel();
    }

    @Override
    public boolean isCancelled() {
        return context.isCancelled();
    }
}
