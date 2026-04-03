package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.MaxTurnsExceededException;
import ai.core.agent.lifecycle.PlanUpdateLifecycle;
import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.OnToolEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
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

    public InProcessAgentSession(String sessionId, Agent agent, boolean autoApproveAll, ToolPermissionStore permissionStore) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.permissionGate = new PermissionGate();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "agent-session-" + sessionId);
            thread.setDaemon(true);
            return thread;
        });

        agent.setStreamingCallback(new SessionStreamingCallback(sessionId, this::dispatch));
        agent.addLifecycle(new ServerPermissionLifecycle(sessionId, this::dispatch, permissionGate, autoApproveAll, permissionStore));
        agent.addLifecycle(new PlanUpdateLifecycle(this::dispatch));
        agent.setAuthenticated(true);

        if (agent.hasPersistenceProvider()) {
            agent.load(sessionId);
        }
    }

    @Override
    public String id() {
        return sessionId;
    }

    public Agent agent() {
        return agent;
    }

    @Override
    public void sendMessage(String message) {
        dispatch(StatusChangeEvent.of(sessionId, SessionStatus.RUNNING));
        agent.resetCancellation();
        Future<?> future = executor.submit(() -> executeAgentRun(message));
        currentTask.set(future);
    }

    private void executeAgentRun(String message) {
        var usageBefore = agent.getCurrentTokenUsage();
        long inputBefore = usageBefore.getPromptTokens();
        long outputBefore = usageBefore.getCompletionTokens();
        try {
            debug("agent run starting");
            var result = agent.run(message);
            if (agent.isCancelled()) {
                debug("agent run cancelled");
                dispatch(TurnCompleteEvent.cancelled(sessionId));
                dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
                return;
            }
            if (agent.hasPersistenceProvider()) {
                agent.save(sessionId);
            }
            debug("agent run completed");
            var turnComplete = TurnCompleteEvent.of(sessionId, result != null ? result : "");
            populateTokenUsage(turnComplete, inputBefore, outputBefore);
            dispatch(turnComplete);
            dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
        } catch (MaxTurnsExceededException e) {
            debug("agent exceeded max turns: " + e.maxTurns);
            if (agent.hasPersistenceProvider()) {
                agent.save(sessionId);
            }
            var turnComplete = TurnCompleteEvent.of(sessionId, agent.getOutput() != null ? agent.getOutput() : "");
            populateTokenUsage(turnComplete, inputBefore, outputBefore);
            turnComplete.maxTurnsReached = true;
            dispatch(turnComplete);
            dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
        } catch (ToolCallDeniedException e) {
            debug("tool call denied: " + e.getMessage());
            dispatch(TurnCompleteEvent.cancelled(sessionId));
            dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
        } catch (Throwable e) {
            if (agent.isCancelled()) {
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
    }

    private void populateTokenUsage(TurnCompleteEvent event, long inputBefore, long outputBefore) {
        var usageAfter = agent.getCurrentTokenUsage();
        event.inputTokens = usageAfter.getPromptTokens() - inputBefore;
        event.outputTokens = usageAfter.getCompletionTokens() - outputBefore;
    }

    @Override
    public void cancelTurn() {
        debug("cancelling current turn");
        agent.cancel();
        // interrupt thread as fallback for when SSE connection hasn't been established yet (thinking phase)
        Future<?> task = currentTask.get();
        if (task != null && !task.isDone()) {
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

    public void loadTools(List<ai.core.tool.ToolCall> tools) {
        agent.addTools(tools);
        logger.info("loaded {} tools to session, sessionId={}", tools.size(), sessionId);
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
                else if (event instanceof OnToolEvent e) listener.onOnTool(e);
                else if (event instanceof PlanUpdateEvent e) listener.onPlanUpdate(e);
            } catch (Exception e) {
                logger.error("failed to dispatch event to listener, event={}, sessionId={}", event.getClass().getSimpleName(), sessionId, e);
            }
        }
    }

    private void debug(String message) {
        if ("true".equals(System.getProperty("core.ai.debug"))) {
            logger.debug("[DEBUG] {}, sessionId={}", message, sessionId);
        }
    }
}
