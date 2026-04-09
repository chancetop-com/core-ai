package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.MaxTurnsExceededException;
import ai.core.agent.lifecycle.PlanUpdateLifecycle;
import ai.core.agent.lifecycle.TaskLifecycle;
import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.CompressionEvent;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.OnToolEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TaskCompletedEvent;
import ai.core.api.server.session.TaskStartEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author stephen
 */
public class InProcessAgentSession implements AgentSession {
    private final Logger logger = LoggerFactory.getLogger(InProcessAgentSession.class);

    private final String sessionId;
    private final Agent agent;
    private final PermissionGate permissionGate;
    private final SessionCommandQueue commandQueue;
    private final TurnDriver turnDriver;
    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile Thread executingThread;

    public InProcessAgentSession(String sessionId, Agent agent, boolean autoApproveAll, ToolPermissionStore permissionStore) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.permissionGate = new PermissionGate();
        this.commandQueue = new SessionCommandQueue();
        this.turnDriver = new TurnDriver(commandQueue, this::executeCommands);
        var context = agent.getExecutionContext();
        if (context.getSubagentOutputSinkFactory() != null) {
            context.setTaskManager(new BackgroundTaskManager(commandQueue, context.getSubagentOutputSinkFactory()));
        }
        agent.setStreamingCallback(new SessionStreamingCallback(sessionId, this::dispatch));
        agent.addLifecycle(new ServerPermissionLifecycle(sessionId, this::dispatch, permissionGate, autoApproveAll, permissionStore));
        agent.addLifecycle(new PlanUpdateLifecycle(this::dispatch));
        agent.addLifecycle(new TaskLifecycle(this::dispatch));
        agent.setAuthenticated(true);
        agent.getExecutionContext().setBackgroundTaskMonitor(taskMonitor);
        setupCompressionListener();

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
        agent.resetCancellation();
        commandQueue.enqueueUserInput(message);
    }

    private void executeCommands(SessionCommandQueue.CommandBatch batch) {
        executingThread = Thread.currentThread();
        dispatch(StatusChangeEvent.of(sessionId, SessionStatus.RUNNING));

        var usageBefore = agent.getCurrentTokenUsage();
        long inputBefore = usageBefore.getPromptTokens();
        long outputBefore = usageBefore.getCompletionTokens();

        try {
            if (batch.mode() == SessionCommandQueue.CommandMode.USER_INPUT) {
                executeUserInput(batch.values(), inputBefore, outputBefore);
            } else if (batch.mode() == SessionCommandQueue.CommandMode.TASK_NOTIFICATION) {
                executeTaskNotifications(batch.values(), inputBefore, outputBefore);
            }
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
            executingThread = null;
        }
    }

    private void executeUserInput(List<String> values, long inputBefore, long outputBefore) {
        var combined = String.join("\n", values);
        debug("agent run starting");
        String result;
        try {
            result = agent.run(combined);
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
            return;
        }
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
    }

    private void executeTaskNotifications(List<String> values, long inputBefore, long outputBefore) {
        var xml = String.join("\n", values);
        debug("injecting task notifications");
        String result;
        try {
            agent.injectUserMessage(xml);
            result = agent.continueWithInjectedMessage();
        } catch (MaxTurnsExceededException e) {
            debug("agent exceeded max turns on notification: " + e.maxTurns);
            if (agent.hasPersistenceProvider()) {
                agent.save(sessionId);
            }
            var turnComplete = TurnCompleteEvent.of(sessionId, agent.getOutput() != null ? agent.getOutput() : "");
            populateTokenUsage(turnComplete, inputBefore, outputBefore);
            turnComplete.maxTurnsReached = true;
            dispatch(turnComplete);
            dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
            return;
        }
        if (agent.isCancelled()) {
            debug("agent run cancelled");
            dispatch(TurnCompleteEvent.cancelled(sessionId));
            dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
            return;
        }
        if (agent.hasPersistenceProvider()) {
            agent.save(sessionId);
        }
        debug("task notifications processed");
        var turnComplete = TurnCompleteEvent.of(sessionId, result != null ? result : "");
        populateTokenUsage(turnComplete, inputBefore, outputBefore);
        dispatch(turnComplete);
        dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
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
        Thread t = executingThread;
        if (t != null) {
            t.interrupt();
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
        turnDriver.shutdown();
    }

    public void loadTools(List<ai.core.tool.ToolCall> tools) {
        agent.addTools(tools);
        logger.info("loaded {} tools to session, sessionId={}", tools.size(), sessionId);
    }

    private void setupCompressionListener() {
        var compression = agent.getCompression();
        if (compression == null) return;
        compression.setListener((beforeCount, afterCount, completed) ->
            dispatch(CompressionEvent.of(sessionId, beforeCount, afterCount, completed)));
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
                else if (event instanceof TaskStartEvent e) listener.onTaskStart(e);
                else if (event instanceof TaskCompletedEvent e) listener.onTaskCompleted(e);
                else if (event instanceof CompressionEvent e) listener.onCompression(e);
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
