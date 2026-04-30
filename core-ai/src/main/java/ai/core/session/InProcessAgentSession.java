package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.MaxTurnsExceededException;
import ai.core.agent.lifecycle.PlanUpdateLifecycle;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.CompressionEvent;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.OnToolEvent;
import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.SandboxEvent;
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
import java.util.Map;
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
        agent.setAuthenticated(true);
        setupCompressionListener();
    }

    /**
     * Constructor that skips loading from persistence (used when session is already loaded).
     */
    public InProcessAgentSession(String sessionId, Agent agent, boolean autoApproveAll, ToolPermissionStore permissionStore, boolean skipLoad) {
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
        agent.setAuthenticated(true);
        setupCompressionListener();
        // Skip loading when skipLoad is true (session already loaded by caller)
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
        sendMessage(message, null);
    }

    @Override
    public void sendMessage(String message, Map<String, Object> variables) {
        agent.resetCancellation();
        commandQueue.enqueueUserInput(message, variables);
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

    private void executeUserInput(List<SessionCommandQueue.QueuedMessage> values, long inputBefore, long outputBefore) {
        var combined = String.join("\n", values.stream().map(SessionCommandQueue.QueuedMessage::value).toList());
        debug("agent run starting");
        String result;
        var context = agent.getExecutionContext();
        var newVariables = values.getLast().variables();
        if (newVariables != null && !newVariables.isEmpty()) {
            context.getCustomVariables().putAll(newVariables);
        }
        try {
            result = agent.run(combined, context);
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

    private void executeTaskNotifications(List<SessionCommandQueue.QueuedMessage> values, long inputBefore, long outputBefore) {
        var xml = String.join("\n", values.stream().map(SessionCommandQueue.QueuedMessage::value).toList());
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

    public void dispatchEvent(AgentEvent event) {
        dispatch(event);
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
                switch (event) {
                    case TextChunkEvent e -> listener.onTextChunk(e);
                    case ReasoningChunkEvent e -> listener.onReasoningChunk(e);
                    case ReasoningCompleteEvent e -> listener.onReasoningComplete(e);
                    case ToolStartEvent e -> listener.onToolStart(e);
                    case ToolResultEvent e -> listener.onToolResult(e);
                    case ToolApprovalRequestEvent e -> listener.onToolApprovalRequest(e);
                    case TurnCompleteEvent e -> listener.onTurnComplete(e);
                    case ErrorEvent e -> listener.onError(e);
                    case StatusChangeEvent e -> listener.onStatusChange(e);
                    case OnToolEvent e -> listener.onOnTool(e);
                    case PlanUpdateEvent e -> listener.onPlanUpdate(e);
                    case CompressionEvent e -> listener.onCompression(e);
                    case SandboxEvent e -> listener.onSandbox(e);
                    default -> {
                    }
                }
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
