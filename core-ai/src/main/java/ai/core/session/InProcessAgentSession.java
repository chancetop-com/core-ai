package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.AttachedContent;
import ai.core.agent.MaxTurnsExceededException;
import ai.core.agent.lifecycle.PlanUpdateLifecycle;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.CompressionEvent;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TaskStatusEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.context.CompressionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * @author stephen
 */
@SuppressWarnings("this-escape")
public class InProcessAgentSession implements AgentSession {
    private final Logger logger = LoggerFactory.getLogger(InProcessAgentSession.class);

    private final String sessionId;
    private final Agent agent;
    private final PermissionGate permissionGate;
    private final SessionCommandQueue commandQueue;
    private final SessionTurnInput turnInput;
    private final TurnDriver turnDriver;
    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    // Set when RUNNING is dispatched, cleared by whoever dispatches the turn's terminal event.
    // close() races the turn thread for it so a force-closed turn still terminates exactly once.
    private final AtomicBoolean turnActive = new AtomicBoolean();
    private volatile long turnStartedAtMs;
    private final SessionCustomEventEmitter customEventEmitter;
    public final SessionFootprintTracker footprint;
    public InProcessAgentSession(String sessionId, Agent agent, boolean autoApproveAll, ToolPermissionStore permissionStore) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.footprint = new SessionFootprintTracker(agent);
        this.permissionGate = new PermissionGate();
        this.commandQueue = new SessionCommandQueue();
        this.turnInput = new SessionTurnInput(commandQueue, agent, sessionId);
        this.turnDriver = new TurnDriver(commandQueue, this::executeCommands);
        var context = agent.getExecutionContext();
        if (context.getSubagentOutputSinkFactory() != null) {
            context.setTaskManager(new BackgroundTaskManager(commandQueue, context.getSubagentOutputSinkFactory(), sessionId, this::dispatch));
        }
        agent.setStreamingCallback(new SessionStreamingCallback(sessionId, this::dispatch, context));
        var permissionLifecycle = new ServerPermissionLifecycle(sessionId, this::dispatch, permissionGate, autoApproveAll, permissionStore, toolTypeResolver());
        agent.addLifecycle(permissionLifecycle);
        agent.addLifecycle(new PlanUpdateLifecycle(this::dispatch));
        agent.setAuthenticated(true);
        this.customEventEmitter = new SessionCustomEventEmitter(sessionId, this::dispatch, turnActive::get);
        context.getCustomVariables().put(CustomEventEmitter.CONTEXT_KEY, customEventEmitter);
        setupCompressionListener();
    }

    /**
     * Constructor that skips loading from persistence (used when session is already loaded).
     */
    public InProcessAgentSession(String sessionId, Agent agent, boolean autoApproveAll, ToolPermissionStore permissionStore, boolean skipLoad) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.footprint = new SessionFootprintTracker(agent);
        this.permissionGate = new PermissionGate();
        this.commandQueue = new SessionCommandQueue();
        this.turnInput = new SessionTurnInput(commandQueue, agent, sessionId);
        this.turnDriver = new TurnDriver(commandQueue, this::executeCommands);
        var context = agent.getExecutionContext();
        if (context.getSubagentOutputSinkFactory() != null) {
            context.setTaskManager(new BackgroundTaskManager(commandQueue, context.getSubagentOutputSinkFactory(), sessionId, this::dispatch));
        }
        agent.setStreamingCallback(new SessionStreamingCallback(sessionId, this::dispatch, context));
        var permissionLifecycle = new ServerPermissionLifecycle(sessionId, this::dispatch, permissionGate, autoApproveAll, permissionStore, toolTypeResolver());
        agent.addLifecycle(permissionLifecycle);
        agent.addLifecycle(new PlanUpdateLifecycle(this::dispatch));
        agent.setAuthenticated(true);
        this.customEventEmitter = new SessionCustomEventEmitter(sessionId, this::dispatch, turnActive::get);
        context.getCustomVariables().put(CustomEventEmitter.CONTEXT_KEY, customEventEmitter);
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
        sendMessage(message, variables, null);
    }

    public void sendMessage(String message, Map<String, Object> variables, List<AttachedContent> attachedContents) {
        if (turnInput.offer(message, variables, attachedContents)) {
            debug("message accepted for the running turn");
            return;
        }
        if (commandQueue.enqueueUserInput(message, variables, attachedContents)) return;
        logger.warn("message refused, session queue is full, sessionId={}", sessionId);
        // A terminal ErrorEvent would clear the running turn's SSE replay buffer and make the
        // client treat the session as finished. Keep the turn alive; the rejection is logged.
        if (turnActive.get()) return;
        // The queue budget is a protection, not a silent drop: say so, and leave the session idle.
        dispatchEvent(ErrorEvent.of(sessionId,
                "too many messages are queued for this session, wait for the running turn to finish", "QUEUE_FULL"));
        dispatchEvent(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
    }

    private void executeCommands(SessionCommandQueue.CommandBatch batch) {
        var executingThread = Thread.currentThread();
        var turnToken = agent.getCancellationToken().createChild();
        agent.getExecutionContext().setCancellationToken(turnToken);
        var threadUnbind = turnToken.bindThread(executingThread);
        var input = turnInput.begin();
        turnActive.set(true);
        turnStartedAtMs = System.currentTimeMillis();
        var turnDeadline = SessionTurnWatchdog.schedule(turnToken, sessionId, turnActive::get);
        customEventEmitter.resetTurn();
        dispatch(StatusChangeEvent.of(sessionId, SessionStatus.RUNNING));

        var usageBefore = agent.getCurrentTokenUsage();
        var before = new SessionTurnUsage.Before(usageBefore.getPromptTokens(), usageBefore.getCompletionTokens(),
                agent.getCurrentCostUsd(),
                usageBefore.getPromptTokensDetails() != null ? usageBefore.getPromptTokensDetails().cachedTokens : 0);

        try {
            if (batch.mode() == SessionCommandQueue.CommandMode.USER_INPUT) {
                executeUserInput(batch.values(), before);
            } else if (batch.mode() == SessionCommandQueue.CommandMode.TASK_NOTIFICATION) {
                executeTaskNotifications(batch.values(), before);
            }
        } catch (ToolCallDeniedException e) {
            debug("tool call denied: " + e.getMessage());
            dispatchTerminal(SessionTurnUsage.cancelled(agent, sessionId, "", before), SessionStatus.IDLE);
            commandQueue.drainTaskNotifications();
        } catch (Throwable e) {
            if (agent.isCancelled()) {
                debug("agent run cancelled");
                dispatchTerminal(SessionTurnUsage.cancelled(agent, sessionId, "", before), SessionStatus.IDLE);
                commandQueue.drainTaskNotifications();
                return;
            }
            debug("agent run failed: " + e);
            logger.warn("agent session run failed, sessionId={}", sessionId, e);
            persistOnFailure();
            dispatchTerminal(ErrorEvent.of(sessionId, e.getMessage(), ""), SessionStatus.ERROR);
        } finally {
            turnInput.end(input);
            turnDeadline.cancel(false);
            threadUnbind.run();
            turnToken.disconnect();
            footprint.refresh();
        }
    }

    private void persistOnFailure() {
        TurnPersistence.save(agent, sessionId, logger, "run failure");
    }

    private void persistCancelledTurn() {
        TurnPersistence.save(agent, sessionId, logger, "cancelled turn");
    }

    private void executeUserInput(List<SessionCommandQueue.QueuedMessage> values, SessionTurnUsage.Before before) {
        var combined = String.join("\n", values.stream().map(SessionCommandQueue.QueuedMessage::value).toList());
        debug("agent run starting");
        String result;
        var context = agent.getExecutionContext();
        var lastMessage = values.getLast();
        var newVariables = lastMessage.variables();
        if (newVariables != null && !newVariables.isEmpty()) {
            context.getCustomVariables().putAll(newVariables);
        }
        context.setAttachedContents(lastMessage.attachedContents());
        try {
            result = agent.run(combined, context);
        } catch (RuntimeException e) {
            if (!dispatchMaxTurns(e, before)) throw e;
            return;
        } finally {
            context.setAttachedContents(null);
        }
        if (agent.isCancelled()) {
            debug("agent run cancelled");
            persistCancelledTurn();
            // carry partial output so server chat history records what was produced before ESC
            dispatchTerminal(SessionTurnUsage.cancelled(agent, sessionId, agent.getOutput(), before), SessionStatus.IDLE);
            commandQueue.drainTaskNotifications();
            return;
        }
        if (agent.hasPersistenceProvider()) {
            agent.save(sessionId);
        }
        debug("agent run completed");
        var turnComplete = TurnCompleteEvent.of(sessionId, result != null ? result : "");
        populateTokenUsage(turnComplete, before);
        dispatchTerminal(turnComplete, SessionStatus.IDLE);
    }

    private void executeTaskNotifications(List<SessionCommandQueue.QueuedMessage> values, SessionTurnUsage.Before before) {
        var xml = String.join("\n", values.stream().map(SessionCommandQueue.QueuedMessage::value).toList());
        debug("injecting task notifications");
        String result;
        try {
            agent.injectUserMessage(xml);
            result = agent.continueWithInjectedMessage();
        } catch (RuntimeException e) {
            if (!dispatchMaxTurns(e, before)) throw e;
            return;
        }
        if (agent.isCancelled()) {
            debug("agent run cancelled");
            persistCancelledTurn();
            // carry partial output so server chat history records what was produced before ESC
            dispatchTerminal(SessionTurnUsage.cancelled(agent, sessionId, agent.getOutput(), before), SessionStatus.IDLE);
            commandQueue.drainTaskNotifications();
            return;
        }
        if (agent.hasPersistenceProvider()) {
            agent.save(sessionId);
        }
        debug("task notifications processed");
        var turnComplete = TurnCompleteEvent.of(sessionId, result);
        populateTokenUsage(turnComplete, before);
        dispatchTerminal(turnComplete, SessionStatus.IDLE);
    }

    /** Consumes a max-turns failure (Node.run wraps it one cause level down) and completes the turn; false = keep propagating. */
    private boolean dispatchMaxTurns(RuntimeException failure, SessionTurnUsage.Before before) {
        MaxTurnsExceededException maxTurns = null;
        if (failure instanceof MaxTurnsExceededException direct) maxTurns = direct;
        else if (failure.getCause() instanceof MaxTurnsExceededException cause) maxTurns = cause;
        if (maxTurns == null) return false;
        debug("agent exceeded max turns: " + maxTurns.maxTurns);
        if (agent.hasPersistenceProvider()) {
            agent.save(sessionId);
        }
        var turnComplete = TurnCompleteEvent.of(sessionId, agent.getOutput() != null ? agent.getOutput() : "");
        populateTokenUsage(turnComplete, before);
        turnComplete.maxTurnsReached = Boolean.TRUE;
        dispatchTerminal(turnComplete, SessionStatus.IDLE);
        return true;
    }

    private void populateTokenUsage(TurnCompleteEvent event, SessionTurnUsage.Before before) {
        SessionTurnUsage.populate(agent, event, before);
    }

    @Override
    public void cancelTurn() {
        debug("cancelling current turn");
        var token = agent.getExecutionContext().getCancellationToken();
        if (token != null) {
            boolean cancelled = token.cancel();
            // Persist the interrupted turn immediately (interruption marker + user message)
            // so it survives process death (e.g. CLI ESC, time limit) before the next save point.
            if (cancelled) {
                agent.persistInterruption(token.getReason());
            }
        } else {
            agent.cancel();
        }
    }

    public void interruptTurn() {
        debug("interrupting current turn");
        var token = agent.getExecutionContext().getCancellationToken();
        if (token != null) {
            token.interrupt();
        }
    }

    @Override
    public void onEvent(AgentEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeEvent(AgentEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void approveToolCall(String callId, ApprovalDecision decision) {
        logger.debug("approveToolCall: callId={}, decision={}, sessionId={}", callId, decision, sessionId);
        permissionGate.respond(callId, decision);
    }

    /**
     * True while the driver thread is actually executing a turn. A turn whose thread died without
     * terminating cleanly reports false, so callers can expire stale turn state instead of trusting it.
     */
    public boolean isTurnRunning() {
        return turnDriver.isProcessing();
    }

    public boolean hasPendingWork() {
        var taskManager = agent.getExecutionContext().getTaskManager();
        return isTurnRunning() || turnInput.hasPending() || !commandQueue.isEmpty()
                || taskManager != null && taskManager.hasRunningTasks();
    }

    /** Messages accepted for the running turn but not yet injected before a model call. */
    public int pendingUserInputCount() {
        return turnInput.pendingCount();
    }

    public void cancelBackgroundTasks() {
        var taskManager = agent.getExecutionContext().getTaskManager();
        if (taskManager != null) taskManager.cancelAllRunning();
    }

    @Override
    public void close() {
        logger.debug("closing agent session, sessionId={}", sessionId);
        turnDriver.shutdown();
        // subagent threads persist after every run; this covers a session closed before that
        var context = agent.getExecutionContext();
        if (context.hasSubagentThreads()) context.getSubagentThreads().persistAll();
        // A forced close (idle cleanup, explicit close, pod shutdown) interrupts the turn thread
        // mid-flight and it may never reach its own terminal dispatch. Emit one here so listeners —
        // turn-state registry, message persistence, SSE clients — never see a turn that runs forever.
        // dispatchTerminal is a CAS, so whichever side gets there first is the only one that fires.
        if (turnActive.get()) {
            logger.warn("session closed while a turn was still running, aborting it, sessionId={}", sessionId);
            dispatchTerminal(TurnCompleteEvent.cancelled(sessionId, agent.getOutput()), SessionStatus.IDLE);
        }
    }

    public void setOnIdle(Runnable onIdle) {
        turnDriver.setOnIdle(onIdle);
    }

    public void loadTools(List<ai.core.tool.ToolCall> tools) {
        agent.addTools(tools);
        logger.info("loaded {} tools to session, sessionId={}", tools.size(), sessionId);
    }

    public void dispatchEvent(AgentEvent event) {
        dispatch(event);
    }

    /**
     * Delivers the terminal state of a long-running tool call the same way a background agent's
     * completion is delivered: queued as a task notification (a turn starts when the session is idle)
     * plus a status event for the UI.
     */
    public void notifyTask(String taskId, String status, String notificationXml) {
        notifyTask(taskId, status, null, notificationXml);
    }

    public void notifyTask(String taskId, String status, String toolName, String notificationXml) {
        commandQueue.enqueueTaskNotification(notificationXml);
        dispatch(TaskStatusEvent.of(sessionId, taskId, status, toolName));
    }

    private void setupCompressionListener() {
        var compression = agent.getCompression();
        if (compression == null) return;
        compression.addListener(report -> {
            var event = compressionEvent(report);
            if (event != null) dispatch(event);
        });
    }

    /**
     * A compression that started and was then given up on leaves the conversation untouched, so there
     * is nothing for a client to show.
     */
    private CompressionEvent compressionEvent(CompressionReport report) {
        if (report.phase() == CompressionReport.Phase.SKIPPED) return null;
        return CompressionEvent.of(sessionId, report.beforeCount(), report.afterCount(), report.completed())
            .withContext(report.contextTokens(), report.maxContextTokens(), report.triggerThreshold());
    }

    /**
     * Ends the current turn exactly once: dispatches the turn-ending event followed by the matching
     * status change. Both the turn thread and {@link #close()} call this, and the CAS guarantees only
     * one of them wins — a turn can neither be terminated twice nor left without a terminal event.
     */
    private void dispatchTerminal(AgentEvent turnEnd, SessionStatus status) {
        if (!turnActive.compareAndSet(true, false)) return;
        if (turnEnd instanceof TurnCompleteEvent turnComplete) {
            turnComplete.durationMs = System.currentTimeMillis() - turnStartedAtMs;
        }
        dispatch(turnEnd);
        dispatch(StatusChangeEvent.of(sessionId, status));
    }

    private void dispatch(AgentEvent event) {
        logger.debug("dispatching event: {}, sessionId={}, thread={}", event.getClass().getSimpleName(), sessionId, Thread.currentThread().getName());
        for (AgentEventListener listener : listeners) {
            try {
                SessionEventDispatcher.dispatch(listener, event);
            } catch (RuntimeException e) {
                logger.error("failed to dispatch event to listener, event={}, sessionId={}", event.getClass().getSimpleName(), sessionId, e);
            }
        }
    }

    private void debug(String message) {
        if ("true".equals(System.getProperty("core.ai.debug"))) {
            logger.debug("[DEBUG] {}, sessionId={}", message, sessionId);
        }
    }

    private Function<String, String> toolTypeResolver() {
        return toolName -> {
            var tools = agent.getToolCalls();
            for (var tool : tools) {
                if (tool.getName().equalsIgnoreCase(toolName)) return tool.getSourceType();
            }
            for (var tool : tools) {
                if (tool.getName().endsWith(toolName)) return tool.getSourceType();
            }
            return "builtin";
        };
    }
}
