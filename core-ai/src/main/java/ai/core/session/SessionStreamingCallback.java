package ai.core.session;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.api.session.AgentEvent;
import ai.core.api.session.ErrorEvent;
import ai.core.api.session.ReasoningChunkEvent;
import ai.core.api.session.ReasoningCompleteEvent;
import ai.core.api.session.TextChunkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class SessionStreamingCallback implements StreamingCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionStreamingCallback.class);

    private final String sessionId;
    private final Consumer<AgentEvent> dispatcher;
    private final AtomicReference<AutoCloseable> activeConnection = new AtomicReference<>();
    private volatile boolean cancelled;

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
        // TurnCompleteEvent is dispatched by InProcessAgentSession after the entire agent run
        // (including all tool calls) is complete, not after each LLM streaming response.
        // Dispatching here would prematurely unblock the REPL loop, causing stdin race conditions
        // between readInput() and askPermission().
    }

    @Override
    public void onError(Throwable error) {
        dispatcher.accept(ErrorEvent.of(sessionId, error.getMessage(), ""));
    }

    @Override
    public void setActiveConnection(AutoCloseable connection) {
        activeConnection.set(connection);
    }

    @Override
    public void cancelConnection() {
        cancelled = true;
        var conn = activeConnection.getAndSet(null);
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception e) {
                LOGGER.debug("close active connection, error={}", e.getMessage());
            }
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void reset() {
        cancelled = false;
        activeConnection.set(null);
    }
}
