package ai.core.session;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.api.session.AgentEvent;
import ai.core.api.session.ErrorEvent;
import ai.core.api.session.ReasoningChunkEvent;
import ai.core.api.session.ReasoningCompleteEvent;
import ai.core.api.session.TextChunkEvent;
import ai.core.api.session.TurnCompleteEvent;

import java.util.function.Consumer;

/**
 * @author stephen
 */
public class SessionStreamingCallback implements StreamingCallback {
    private final String sessionId;
    private final Consumer<AgentEvent> dispatcher;

    public SessionStreamingCallback(String sessionId, Consumer<AgentEvent> dispatcher) {
        this.sessionId = sessionId;
        this.dispatcher = dispatcher;
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
    public void onToolStart(String toolName) {
        // Here we map toolName to a generic callId, as the callback doesn't have callId.
        // For accurate tool tracking, the Lifecycle will handle specific ToolStartEvent with callId.
    }

    @Override
    public void onToolResult(String toolName, String result) {
        // Similar to onToolStart, the Lifecycle is better suited for specific results.
    }

    @Override
    public void onComplete() {
        dispatcher.accept(TurnCompleteEvent.of(sessionId, ""));
    }

    @Override
    public void onError(Throwable error) {
        dispatcher.accept(ErrorEvent.of(sessionId, error.getMessage(), ""));
    }
}
