package ai.core.session;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.EnvironmentOutputChunkEvent;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.OnToolEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.TextChunkEvent;

import ai.core.llm.domain.FunctionCall;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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
