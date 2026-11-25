package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.AttachedContent;
import ai.core.agent.CancelReason;
import ai.core.agent.CancellationToken;
import ai.core.agent.MaxTurnsExceededException;
import ai.core.agent.lifecycle.PlanUpdateLifecycle;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.CompressionEvent;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.LlmFirstTokenEvent;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TaskStatusEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.context.CompressionReport;
import ai.core.session.lifecycle.TaskRunCoordinator;
import ai.core.session.lifecycle.TaskRunOutcome;
import ai.core.session.transcript.TranscriptInput;
import ai.core.session.transcript.TranscriptKinds;
import ai.core.session.transcript.TranscriptRecorder;
import ai.core.session.transcript.TranscriptUsage;
import ai.core.tool.tools.GetVideoStatusTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * @author stephen
 */
@SuppressWarnings("this-escape")
public class InProcessAgentSession implements AgentSession {
    private static final Set<String> UNTRACKED_PENDING_TOOLS = Set.of("generate_image");

    private static TaskRunOutcome outcomeOfStatus(String status) {
        if ("completed".equals(status)) return TaskRunOutcome.RETURNED;
        if ("cancelled".equals(status)) return TaskRunOutcome.CANCELLED;
        return TaskRunOutcome.FAILED;
    }

    private static TaskRunOutcome terminalOutcome(AgentEvent turnEnd) {
        if (turnEnd instanceof TurnCompleteEvent turnComplete) {
            if (Boolean.TRUE.equals(turnComplete.maxTurnsReached)) return TaskRunOutcome.LIMITED;
            if (Boolean.TRUE.equals(turnComplete.cancelled)) return TaskRunOutcome.CANCELLED;
            return TaskRunOutcome.RETURNED;
        }
        if (turnEnd instanceof ErrorEvent) return TaskRunOutcome.FAILED;
        return TaskRunOutcome.INTERRUPTED;
    }

    private static String terminalReason(AgentEvent turnEnd) {
        if (turnEnd instanceof TurnCompleteEvent turnComplete) {
            if (Boolean.TRUE.equals(turnComplete.maxTurnsReached)) return "max_turns_exceeded";
            if (Boolean.TRUE.equals(turnComplete.cancelled)) return "user_cancelled";
            return null;
        }
        if (turnEnd instanceof ErrorEvent error) return error.message;
        return "turn_terminated";
    }

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
    private volatile String currentTurnId;
    private volatile String currentExecutionId;
    private volatile TaskRunCoordinator.ExecutionKind currentExecutionKind;
    private volatile long turnStartedAtMs;
    private final AtomicLong turnWaitingMs = new AtomicLong();
    private final SessionCustomEventEmitter customEventEmitter;
    private final TranscriptRecorder recorder;
    private final TaskRunCoordinator taskRuns;
    private final Set<String> watchingVideoJobs = ConcurrentHashMap.newKeySet();
    private final Set<String> settledVideoJobs = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public final SessionFootprintTracker footprint;
    public InProcessAgentSession(String sessionId, Agent agent, boolean autoApproveAll, ToolPermissionStore permissionStore) {
        this(sessionId, agent, autoApproveAll, permissionStore, false, null);
    }

    /**
     * Constructor that skips loading from persistence (used when session is already loaded).
     */
    public InProcessAgentSession(String sessionId, Agent agent, boolean autoApproveAll, ToolPermissionStore permissionStore, boolean skipLoad) {
        this(sessionId, agent, autoApproveAll, permissionStore, skipLoad, null);
    }

    public InProcessAgentSession(String sessionId, Agent agent, boolean autoApproveAll, ToolPermissionStore permissionStore,
                                 boolean skipLoad, TranscriptRecorder recorder) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.recorder = recorder;
        this.footprint = new SessionFootprintTracker(agent);
        this.permissionGate = new PermissionGate();
        this.commandQueue = new SessionCommandQueue();
        this.turnInput = new SessionTurnInput(agent);
        this.turnDriver = new TurnDriver(commandQueue, this::executeCommands);
        this.taskRuns = new TaskRunCoordinator(sessionId, "inc-" + UUID.randomUUID());
        this.taskRuns.setPendingWorkProbe(this::hasDeferredWork);
        var context = agent.getExecutionContext();
        context.setWaitEventListener(this::dispatch);
        if (context.getSubagentOutputSinkFactory() != null) {
            context.setTaskManager(new BackgroundTaskManager(commandQueue, context.getSubagentOutputSinkFactory(), sessionId, this::dispatch, taskRuns));
        }
        agent.setStreamingCallback(new SessionStreamingCallback(sessionId, this::dispatch, context, this::onLlmFirstToken));
        var permissionLifecycle = new ServerPermissionLifecycle(sessionId, this::dispatch, permissionGate, autoApproveAll, permissionStore, toolTypeResolver());
        agent.addLifecycle(permissionLifecycle);
        agent.addLifecycle(new PlanUpdateLifecycle(this::dispatch));
        agent.setAuthenticated(true);
        this.customEventEmitter = new SessionCustomEventEmitter(sessionId, this::dispatch, turnActive::get);
        context.getCustomVariables().put(CustomEventEmitter.CONTEXT_KEY, customEventEmitter);
        if (recorder != null) {
            context.getCustomVariables().put(TranscriptRecorder.CONTEXT_KEY, recorder);
        }
        setupCompressionListener();
        // Skip loading when skipLoad is true (session already loaded by caller)
    }

    public TranscriptRecorder recorder() {
        return recorder;
    }

    @Override
    public String id() {
        return sessionId;
    }
    public Agent agent() {
        return agent;
    }

    public TaskRunCoordinator taskRuns() {
        return taskRuns;
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
        sendMessage(message, variables, attachedContents, null);
    }

    public void sendMessage(String message, Map<String, Object> variables, List<AttachedContent> attachedContents, String turnId) {
        sendTrackedMessage(message, variables, attachedContents, turnId);
    }

    public SendDisposition sendTrackedMessage(String message, Map<String, Object> variables,
                                              List<AttachedContent> attachedContents, String turnId) {
        if (turnInput.isActive() && (recorder == null || recorder.hasOpenTurn())) {
            var input = new TranscriptInput(message, attachedContents, turnId);
            var steerSeq = recorder == null ? null : recorder.steerPending(input);
            if (turnInput.offer(message, variables, attachedContents, turnId, steerSeq)) {
                debug("message accepted for the running turn");
                return SendDisposition.STEERED;
            }
            if (recorder != null && steerSeq != null) recorder.steerCancel(steerSeq);
        }
        if (commandQueue.enqueueUserInput(message, variables, attachedContents, turnId)) return SendDisposition.QUEUED;
        logger.warn("message refused, session queue is full, sessionId={}", sessionId);
        // A terminal ErrorEvent would clear the running turn's SSE replay buffer and make the
        // client treat the session as finished. Keep the turn alive; the rejection is logged.
        if (turnActive.get()) return SendDisposition.REJECTED;
        // The queue budget is a protection, not a silent drop: say so, and leave the session idle.
        dispatchEvent(ErrorEvent.of(sessionId,
                "too many messages are queued for this session, wait for the running turn to finish", "QUEUE_FULL"));
        dispatchEvent(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
        return SendDisposition.REJECTED;
    }

    /**
     * Queues a user message behind any running turn instead of steering it into the current turn.
     * Used when another session forwards a message and its turn must stay independent.
     */
    public boolean enqueueMessage(String message, Map<String, Object> variables, List<AttachedContent> attachedContents, String turnId) {
        return commandQueue.enqueueUserInput(message, variables, attachedContents, turnId);
    }

    private void executeCommands(SessionCommandQueue.CommandBatch batch) {
        if (batch.mode() == SessionCommandQueue.CommandMode.USER_INPUT && deferUserBatch(batch)) return;
        var executingThread = Thread.currentThread();
        var turnToken = agent.getCancellationToken().createChild();
        agent.getExecutionContext().setCancellationToken(turnToken);
        var threadUnbind = turnToken.bindThread(executingThread);
        turnInput.begin();
        var turnId = batch.mode() == SessionCommandQueue.CommandMode.USER_INPUT && !batch.values().isEmpty()
                ? batch.values().getFirst().turnId()
                : null;
        currentTurnId = turnId;
        beginExecution(batch, turnId);
        turnActive.set(true);
        turnStartedAtMs = System.currentTimeMillis();
        turnWaitingMs.set(0);
        var turnDeadline = SessionTurnWatchdog.schedule(turnToken, sessionId, turnActive::get);
        customEventEmitter.resetTurn();
        dispatch(StatusChangeEvent.of(sessionId, SessionStatus.RUNNING));

        var before = turnUsageBefore();
        try {
            if (batch.mode() == SessionCommandQueue.CommandMode.USER_INPUT) {
                executeUserInput(batch.values(), before, turnId);
            } else if (batch.mode() == SessionCommandQueue.CommandMode.TASK_NOTIFICATION) {
                executeTaskNotifications(batch.values(), before);
            }
        } catch (ToolCallDeniedException e) {
            debug("tool call denied: " + e.getMessage());
            var cancelled = SessionTurnUsage.cancelled(agent, sessionId, "", before);
            cancelled.turnId = turnId;
            dispatchTerminal(cancelled, SessionStatus.IDLE);
            commandQueue.drainTaskNotifications();
        } catch (Throwable e) {
            if (agent.isCancelled()) {
                debug("agent run cancelled");
                var cancelled = SessionTurnUsage.cancelled(agent, sessionId, "", before);
                cancelled.turnId = turnId;
                dispatchTerminal(cancelled, SessionStatus.IDLE);
                commandQueue.drainTaskNotifications();
                return;
            }
            debug("agent run failed: " + e);
            logger.warn("agent session run failed, sessionId={}", sessionId, e);
            persistOnFailure();
            var error = ErrorEvent.of(sessionId, e.getMessage(), "", turnId);
            recordFailedTurn(error, SessionTurnUsage.of(agent, before));
            dispatchTerminal(error, SessionStatus.ERROR);
        } finally {
            finishTurn(turnDeadline, threadUnbind, turnToken);
        }
    }

    private boolean deferUserBatch(SessionCommandQueue.CommandBatch batch) {
        var turnIds = batch.values().stream().map(SessionCommandQueue.QueuedMessage::turnId)
                .filter(value -> value != null).toList();
        if (taskRuns.shouldDeferUserBatch(turnIds)) {
            commandQueue.requeueUserInputs(batch.values());
            taskRuns.awaitChange(500);
            return true;
        }
        taskRuns.claimQueued(turnIds);
        return false;
    }

    private void beginExecution(SessionCommandQueue.CommandBatch batch, String turnId) {
        var kind = batch.mode() == SessionCommandQueue.CommandMode.USER_INPUT
                ? TaskRunCoordinator.ExecutionKind.ROOT
                : TaskRunCoordinator.ExecutionKind.TASK_NOTIFICATION;
        var executionId = turnId != null ? turnId : "exec-" + UUID.randomUUID();
        currentExecutionId = executionId;
        currentExecutionKind = kind;
        taskRuns.executionStarted(executionId, kind, batch.values().size());
    }

    private void finishTurn(ScheduledFuture<?> turnDeadline, Runnable threadUnbind, CancellationToken turnToken) {
        for (var pending : turnInput.end()) {
            if (recorder != null && pending.steerSeq() != null) recorder.steerCancel(pending.steerSeq());
            if (!commandQueue.enqueueUserInput(pending.message(), pending.variables(), pending.attachedContents(), pending.turnId())) {
                logger.warn("failed to requeue message accepted during the turn, sessionId={}", sessionId);
            }
        }
        turnDeadline.cancel(false);
        threadUnbind.run();
        turnToken.disconnect();
        currentTurnId = null;
        // the last tool result of a turn may be the biggest image the session holds; drop what the
        // retention ceiling does not allow before the footprint is refreshed
        agent.applyRetentionBudget();
        footprint.refresh();
        taskRuns.pendingWorkChanged();
    }

    private SessionTurnUsage.Before turnUsageBefore() {
        var usage = agent.getCurrentTokenUsage();
        return new SessionTurnUsage.Before(usage.getPromptTokens(), usage.getCompletionTokens(),
                agent.getCurrentCostUsd(),
                usage.getPromptTokensDetails() != null ? usage.getPromptTokensDetails().cachedTokens : 0);
    }

    private void beginUserTurn(List<SessionCommandQueue.QueuedMessage> values) {
        if (recorder == null) return;
        var inputs = new ArrayList<TranscriptInput>();
        for (var value : values) {
            inputs.add(new TranscriptInput(value.value(), value.attachedContents(), value.turnId()));
        }
        recorder.beginTurn(inputs, TranscriptKinds.SOURCE_USER, agent.getModel(), null);
    }

    private void beginTaskTurn(List<SessionCommandQueue.QueuedMessage> values) {
        if (recorder == null) return;
        var inputs = new ArrayList<TranscriptInput>();
        for (var value : values) {
            inputs.add(new TranscriptInput(value.value(), null, null));
        }
        recorder.beginTurn(inputs, TranscriptKinds.SOURCE_TASK_NOTE, agent.getModel(), null);
    }

    private void persistOnFailure() {
        Thread.interrupted();
        TurnPersistence.save(agent, sessionId, logger, "run failure");
    }

    private void persistCancelledTurn() {
        TurnPersistence.save(agent, sessionId, logger, "cancelled turn");
    }

    private void executeUserInput(List<SessionCommandQueue.QueuedMessage> values, SessionTurnUsage.Before before, String turnId) {
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
        beginUserTurn(values);
        try {
            result = agent.run(combined, context);
        } catch (RuntimeException e) {
            if (!dispatchMaxTurns(e, before, turnId)) throw e;
            return;
        } finally {
            // cancelTurn() interrupts this thread; the interrupt has done its job once the run returned,
            // and it must not break the persistence and transcript writes that finish the turn
            Thread.interrupted();
            context.setAttachedContents(null);
        }
        if (agent.isCancelled()) {
            debug("agent run cancelled");
            persistCancelledTurn();
            // carry partial output so server chat history records what was produced before ESC
            var cancelled = SessionTurnUsage.cancelled(agent, sessionId, agent.getOutput(), before);
            cancelled.turnId = turnId;
            dispatchTerminal(cancelled, SessionStatus.IDLE);
            commandQueue.drainTaskNotifications();
            return;
        }
        if (agent.hasPersistenceProvider()) {
            agent.save(sessionId);
        }
        debug("agent run completed");
        var turnComplete = TurnCompleteEvent.of(sessionId, result != null ? result : "", turnId);
        populateTokenUsage(turnComplete, before);
        dispatchTerminal(turnComplete, SessionStatus.IDLE);
    }

    private void executeTaskNotifications(List<SessionCommandQueue.QueuedMessage> values, SessionTurnUsage.Before before) {
        var xml = String.join("\n", values.stream().map(SessionCommandQueue.QueuedMessage::value).toList());
        debug("injecting task notifications");
        String result;
        beginTaskTurn(values);
        try {
            agent.injectUserMessage(xml);
            result = agent.continueWithInjectedMessage();
        } catch (RuntimeException e) {
            if (!dispatchMaxTurns(e, before, null)) throw e;
            return;
        } finally {
            Thread.interrupted();
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
    private boolean dispatchMaxTurns(RuntimeException failure, SessionTurnUsage.Before before, String turnId) {
        MaxTurnsExceededException maxTurns = null;
        if (failure instanceof MaxTurnsExceededException direct) maxTurns = direct;
        else if (failure.getCause() instanceof MaxTurnsExceededException cause) maxTurns = cause;
        if (maxTurns == null) return false;
        debug("agent exceeded max turns: " + maxTurns.maxTurns);
        if (agent.hasPersistenceProvider()) {
            agent.save(sessionId);
        }
        var turnComplete = TurnCompleteEvent.of(sessionId, agent.getOutput() != null ? agent.getOutput() : "", turnId);
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
        if (recorder != null) recorder.approval(callId, decision);
    }

    /**
     * True while the driver thread is actually executing a turn. A turn whose thread died without
     * terminating cleanly reports false, so callers can expire stale turn state instead of trusting it.
     */
    public boolean isTurnRunning() {
        return turnDriver.isProcessing();
    }

    /** Epoch millis when the current turn started; only meaningful while {@link #isTurnRunning()}. */
    public long turnStartedAtMillis() {
        return turnStartedAtMs;
    }

    public boolean hasPendingWork() {
        var taskManager = agent.getExecutionContext().getTaskManager();
        return isTurnRunning() || turnInput.hasPending() || !commandQueue.isEmpty()
                || taskManager != null && taskManager.hasRunningTasks();
    }

    /**
     * Deferred work only: the driver's own running turn is already represented by the coordinator's
     * active execution and must not keep a run open after that execution has ended.
     */
    private boolean hasDeferredWork() {
        var taskManager = agent.getExecutionContext().getTaskManager();
        return turnInput.hasPending() || taskManager != null && taskManager.hasRunningTasks();
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
        closed = true;
        taskRuns.interrupt("session_closed");
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
            // The interrupt alone wakes a parked wait but leaves the agent loop running (it would try
            // another model call); cancelling the turn token is what actually ends the turn.
            var token = agent.getExecutionContext().getCancellationToken();
            if (token != null) token.cancel(CancelReason.USER_CANCELLED);
            TurnPersistence.save(agent, sessionId, logger, "forced close");
            var cancelled = TurnCompleteEvent.cancelled(sessionId, agent.getOutput());
            cancelled.turnId = currentTurnId;
            dispatchTerminal(cancelled, SessionStatus.IDLE);
        }
        if (recorder != null) {
            try {
                recorder.close();
            } catch (RuntimeException e) {
                logger.warn("failed to close the transcript, sessionId={}", sessionId, e);
            }
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
        taskRuns.taskSettled(taskId, 0L, outcomeOfStatus(status), true, status);
        boolean accepted = commandQueue.enqueueTaskNotification(notificationXml);
        taskRuns.notificationQueued(taskId, 0L, accepted);
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
        // a cancel interrupt must never keep the terminal event from reaching the clients
        Thread.interrupted();
        if (!turnActive.compareAndSet(true, false)) return;
        if (turnEnd instanceof TurnCompleteEvent turnComplete) {
            turnComplete.durationMs = System.currentTimeMillis() - turnStartedAtMs;
            var waitingMs = turnWaitingMs.get();
            if (waitingMs > 0) turnComplete.waitingMs = waitingMs;
            if (recorder != null) {
                try {
                    recorder.completeTurn(turnComplete);
                } catch (RuntimeException e) {
                    // the transcript is a side record; a write failure must never swallow the terminal
                    // event, or every client waits for a turn end that will never arrive
                    logger.warn("failed to record turn completion, sessionId={}", sessionId, e);
                }
            }
        }
        var executionId = currentExecutionId;
        var executionKind = currentExecutionKind;
        currentExecutionId = null;
        currentExecutionKind = null;
        if (executionId != null && executionKind != null) {
            taskRuns.executionEnded(executionId, executionKind, terminalOutcome(turnEnd), terminalReason(turnEnd));
        }
        dispatch(turnEnd);
        dispatch(StatusChangeEvent.of(sessionId, status));
    }

    private void recordFailedTurn(ErrorEvent error, TranscriptUsage usage) {
        if (recorder == null) return;
        try {
            recorder.failTurn(error, usage);
        } catch (RuntimeException e) {
            logger.warn("failed to record the failed turn, sessionId={}", sessionId, e);
        }
    }

    private void observeToolResult(ToolResultEvent event) {
        if (event.toolName == null) return;
        if ("generate_video".equals(event.toolName) && "pending".equals(event.status)) {
            startVideoWatcher(event.taskId);
            return;
        }
        if ("get_video_status".equals(event.toolName)
                && ("success".equals(event.status) || "failure".equals(event.status))) {
            settleVideoFromPoll(event.taskId, "success".equals(event.status));
            return;
        }
        if ("pending".equals(event.status) && UNTRACKED_PENDING_TOOLS.contains(event.toolName)) {
            taskRuns.markUntrackable("required_adapter_not_wired:" + event.toolName);
        }
    }

    private void startVideoWatcher(String videoId) {
        if (videoId == null || closed) {
            taskRuns.markUntrackable("video_status_unavailable");
            return;
        }
        var context = agent.getExecutionContext();
        var provider = context.getVideoMediaProvider();
        if (provider == null) {
            taskRuns.markUntrackable("video_provider_not_configured");
            return;
        }
        if (!watchingVideoJobs.add(videoId)) return;
        taskRuns.taskLaunched(videoId, 0L);
        Thread.ofVirtual().name("video-watch-" + videoId).start(() -> watchVideo(videoId, context, provider));
    }

    private void watchVideo(String videoId, ai.core.agent.ExecutionContext context, ai.core.media.MediaProvider provider) {
        int errors = 0;
        for (int attempt = 0; attempt < 360 && !closed; attempt++) {
            if (settledVideoJobs.contains(videoId)) return;
            try {
                var status = provider.getVideoStatus(videoId);
                if ("completed".equals(status.status())) {
                    var result = GetVideoStatusTool.builder().build()
                            .execute("{\"video_id\":\"" + videoId + "\"}", context);
                    if (settledVideoJobs.contains(videoId)) return;
                    if (result.isCompleted()) {
                        finishVideo(videoId, "completed", result.getResult(), null);
                    } else {
                        finishVideo(videoId, "failed", null, result.getResult());
                    }
                    return;
                }
                if ("failed".equals(status.status())) {
                    finishVideo(videoId, "failed", null, status.error() != null ? status.error() : "video generation failed");
                    return;
                }
                errors = 0;
            } catch (RuntimeException e) {
                errors++;
                if (errors >= 12) {
                    taskRuns.markNeedsAttention("video_status_unavailable");
                    return;
                }
            }
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!closed && !settledVideoJobs.contains(videoId)) {
            taskRuns.markNeedsAttention("video_watch_timeout");
        }
    }

    private void finishVideo(String videoId, String status, String result, String error) {
        if (closed || !settledVideoJobs.add(videoId)) return;
        watchingVideoJobs.remove(videoId);
        logger.info("video task settled, videoId={}, status={}, error={}", videoId, status, error);
        var xml = TaskNotificationText.buildNotificationXml(videoId, status, null, result, error, 0L);
        notifyTask(videoId, status, "generate_video", xml);
    }

    private void settleVideoFromPoll(String videoId, boolean completed) {
        if (videoId == null || !settledVideoJobs.add(videoId)) return;
        watchingVideoJobs.remove(videoId);
        taskRuns.taskSettled(videoId, 0L, completed ? TaskRunOutcome.RETURNED : TaskRunOutcome.FAILED, false,
                completed ? "completed" : "video generation failed");
    }

    private void onLlmFirstToken(long ttftMs) {
        turnWaitingMs.addAndGet(ttftMs);
        dispatch(LlmFirstTokenEvent.of(sessionId, ttftMs));
    }

    private void dispatch(AgentEvent event) {
        if (event instanceof ToolResultEvent toolResult) observeToolResult(toolResult);
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

    public enum SendDisposition {
        STEERED,
        QUEUED,
        REJECTED
    }
}
