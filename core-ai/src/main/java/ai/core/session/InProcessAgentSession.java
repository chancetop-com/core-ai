package ai.core.session;

import ai.core.agent.Agent;
import ai.core.api.session.AgentEvent;
import ai.core.api.session.AgentEventListener;
import ai.core.api.session.AgentSession;
import ai.core.api.session.ApprovalDecision;
import ai.core.api.session.ErrorEvent;
import ai.core.api.session.ReasoningChunkEvent;
import ai.core.api.session.ReasoningCompleteEvent;
import ai.core.api.session.SessionStatus;
import ai.core.api.session.StatusChangeEvent;
import ai.core.api.session.TextChunkEvent;
import ai.core.api.session.ToolApprovalRequestEvent;
import ai.core.api.session.ToolResultEvent;
import ai.core.api.session.ToolStartEvent;
import ai.core.api.session.TurnCompleteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author stephen
 */
public class InProcessAgentSession implements AgentSession {
    private final Logger logger = LoggerFactory.getLogger(InProcessAgentSession.class);

    private final String sessionId;
    private final Agent agent;
    private final PermissionGate permissionGate;
    private final ExecutorService executor;
    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<Future<?>> currentTask = new AtomicReference<>();

    public InProcessAgentSession(String sessionId, Agent agent, boolean autoApproveAll) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.permissionGate = new PermissionGate();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "agent-session-" + sessionId);
            thread.setDaemon(true);
            return thread;
        });

        agent.setStreamingCallback(new SessionStreamingCallback(sessionId, this::dispatch));
        agent.addLifecycle(new ServerPermissionLifecycle(sessionId, this::dispatch, permissionGate, autoApproveAll));

        if (agent.hasPersistenceProvider()) {
            agent.load(sessionId);
        }
    }

    @Override
    public String id() {
        return sessionId;
    }

    @Override
    public void sendMessage(String message) {
        dispatch(StatusChangeEvent.of(sessionId, SessionStatus.RUNNING));
        Future<?> future = executor.submit(() -> {
            try {
                debug("agent run starting");
                var result = agent.run(message);
                if (agent.hasPersistenceProvider()) {
                    agent.save(sessionId);
                }
                debug("agent run completed");
                dispatch(TurnCompleteEvent.of(sessionId, result != null ? result : ""));
                dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
            } catch (Throwable e) {
                if (Thread.currentThread().isInterrupted()) {
                    debug("agent run cancelled");
                    dispatch(TurnCompleteEvent.cancelled(sessionId));
                    dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
                    return;
                }
                debug("agent run failed: " + e);
                logger.warn("agent session run failed, sessionId={}, error={}", sessionId, e.getMessage());
                dispatch(ErrorEvent.of(sessionId, e.getMessage(), ""));
                dispatch(StatusChangeEvent.of(sessionId, SessionStatus.ERROR));
            } finally {
                currentTask.compareAndSet(currentTask.get(), null);
            }
        });
        currentTask.set(future);  // safe: single-thread executor ensures task hasn't started yet
    }

    @Override
    public void cancelTurn() {
        Future<?> task = currentTask.getAndSet(null);
        if (task != null && !task.isDone()) {
            debug("cancelling current turn");
            task.cancel(true);
        }
    }

    @Override
    public void onEvent(AgentEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void approveToolCall(String callId, ApprovalDecision decision) {
        logger.debug("approveToolCall: callId={}, decision={}, sessionId={}", callId, decision, sessionId);
        permissionGate.respond(callId, decision);
    }

    @Override
    public void close() {
        logger.debug("closing agent session, sessionId={}", sessionId);
        executor.shutdownNow();
    }

    private void dispatch(AgentEvent event) {
        logger.debug("dispatching event: {}, sessionId={}, thread={}", event.getClass().getSimpleName(), sessionId, Thread.currentThread().getName());
        for (AgentEventListener listener : listeners) {
            try {
                if (event instanceof TextChunkEvent e) listener.onTextChunk(e);
                else if (event instanceof ReasoningChunkEvent e) listener.onReasoningChunk(e);
                else if (event instanceof ReasoningCompleteEvent e) listener.onReasoningComplete(e);
                else if (event instanceof ToolStartEvent e) listener.onToolStart(e);
                else if (event instanceof ToolResultEvent e) listener.onToolResult(e);
                else if (event instanceof ToolApprovalRequestEvent e) listener.onToolApprovalRequest(e);
                else if (event instanceof TurnCompleteEvent e) listener.onTurnComplete(e);
                else if (event instanceof ErrorEvent e) listener.onError(e);
                else if (event instanceof StatusChangeEvent e) listener.onStatusChange(e);
            } catch (Exception e) {
                logger.warn("failed to dispatch event to listener, event={}, sessionId={}", event.getClass().getSimpleName(), sessionId, e);
            }
        }
    }

    private void debug(String message) {
        if ("true".equals(System.getProperty("core.ai.debug"))) {
            logger.warn("[DEBUG] {}, sessionId={}", message, sessionId);
        }
    }
}
