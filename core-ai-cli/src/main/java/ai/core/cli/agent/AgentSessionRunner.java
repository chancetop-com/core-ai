package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.cli.appserver.WorkspaceDirs;
import ai.core.cli.command.HandlerContext;
import ai.core.cli.command.MemoryCommandHandler;
import ai.core.cli.command.ReplCommandHandler;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.hook.ScriptHookLifecycle;
import ai.core.cli.listener.CliEventListener;
import ai.core.cli.listener.CompressionProgressListener;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MemoryTriggerService;
import ai.core.cli.memory.SessionCloseExtractor;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.BannerPrinter;
import ai.core.cli.ui.FileReferenceExpander;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.upgrade.VersionUtil;
import ai.core.llm.LLMProviders;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionLoad;
import ai.core.session.SessionLoads;
import ai.core.session.SessionLock;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence;
import ai.core.session.ToolPermissionStore;
import ai.core.session.TurnPersistence;
import ai.core.session.transcript.TranscriptMetadata;
import ai.core.session.transcript.TranscriptRecorder;
import ai.core.session.transcript.TranscriptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class AgentSessionRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSessionRunner.class);
    private static final String POISON_PILL = "\0__EXIT__";
    private static final int DEFAULT_BACKGROUND_WAIT_SECONDS = 600;
    private static final long BUDGET_EXIT_BUFFER_MILLIS = 20_000;

    static Long promptTimeoutMillis(ai.core.agent.budget.RunBudget runBudget, Integer timeLimitSeconds) {
        Long budgetTimeout = null;
        if (runBudget != null && runBudget.time() != null) {
            // the reserve belongs to the model; only keep a small buffer for session save and trace flush
            budgetTimeout = Math.max(0, runBudget.time().remainingMillis() - BUDGET_EXIT_BUFFER_MILLIS);
        }
        if (timeLimitSeconds != null && timeLimitSeconds > 0) {
            long legacyTimeout = timeLimitSeconds * 1000L;
            return budgetTimeout == null ? legacyTimeout : Math.min(budgetTimeout, legacyTimeout);
        }
        return budgetTimeout;
    }

    private final TerminalUI ui;
    private final Agent agent;
    private final String modelName;
    private final boolean autoApproveAll;
    private final String sessionId;
    private final SessionManager sessionManager;
    private final SessionPersistence sessionPersistence;
    private final ToolPermissionStore permissionStore;
    private final MemoryCommandHandler memoryCommand;
    private final boolean memoryEnabled;
    private final boolean dailyLogsEnabled;
    private final boolean promptExtractionEnabled;
    private final Integer timeLimitSeconds;
    private final int backgroundWaitSeconds;
    private final ai.core.agent.budget.RunBudget runBudget;
    private final Path workspace;
    private final Path sessionsDir;
    private TranscriptRecorder recorder;
    private final AtomicReference<String> switchSessionId = new AtomicReference<>();
    private final SessionUpgradeHandler upgradeHandler;
    private final ModelPicker modelPicker;
    private ReplCommandHandler commands;
    private final String defaultServerUrl;
    private final ai.core.schedule.ScheduledTaskStore scheduledTaskStore;
    private SessionScheduleTicker scheduleTicker;

    public AgentSessionRunner(TerminalUI ui, Agent agent, LLMProviders llmProviders, Config config) {
        this.ui = ui;
        this.agent = agent;
        this.modelName = config.modelName;
        this.autoApproveAll = config.autoApproveAll;
        this.sessionId = config.sessionId;
        this.sessionManager = config.sessionManager;
        this.sessionPersistence = config.sessionPersistence;
        this.permissionStore = config.permissionStore;
        ModelRegistry modelRegistry = config.modelRegistry;
        this.workspace = Path.of((String) agent.getExecutionContext().getCustomVariables().get("workspace"));
        this.sessionsDir = WorkspaceDirs.ensure(this.workspace);
        this.memoryCommand = config.memoryEnabled
                ? new MemoryCommandHandler(ui, config.memory, MemoryTriggerService.getInstance(workspace))
                : null;
        this.memoryEnabled = config.memoryEnabled;
        this.dailyLogsEnabled = config.dailyLogsEnabled;
        this.promptExtractionEnabled = config.promptExtractionEnabled;
        this.timeLimitSeconds = config.timeLimitSeconds;
        this.backgroundWaitSeconds = config.backgroundWaitSeconds != null
                ? config.backgroundWaitSeconds : DEFAULT_BACKGROUND_WAIT_SECONDS;
        this.runBudget = config.runBudget();
        if (config.timeLimitSeconds != null && config.timeLimitSeconds > 0) {
            agent.getExecutionContext().getCustomVariables().put("time_limit_seconds", config.timeLimitSeconds);
        }
        this.upgradeHandler = new SessionUpgradeHandler(ui);
        this.modelPicker = new ModelPicker(ui, agent, llmProviders, modelRegistry);
        this.defaultServerUrl = config.defaultServerUrl;
        this.scheduledTaskStore = config.scheduledTaskStore;
    }

    public String run() {
        try (var lock = acquireLock()) {
            if (lock == null) return null;
            return runLocked();
        }
    }

    private String runLocked() {
        if (!loadSession()) return null;
        if (memoryEnabled && agent.hasPersistenceProvider() && !agent.getHistory().isEmpty()) {
            MemoryTriggerService.getInstance(workspace).resetCursorToEnd();
        }
        recorder = createRecorder();
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore, false, recorder);
        var listener = new CliEventListener(ui, session, agent);
        session.onEvent(recorder);
        session.onEvent(listener);
        setupCompressionListener(listener);
        commands = new ReplCommandHandler(ui, workspace);
        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        Semaphore readyForInput = new Semaphore(1);
        printBanner();
        upgradeHandler.checkAndHintUpgrade();
        printSessionHistory();
        SessionTurnSender.startSenderThread(messageQueue, listener, session, readyForInput, POISON_PILL);
        startScheduleTicker(messageQueue);
        readInputLoop(messageQueue, readyForInput);
        stopScheduleTicker();
        listener.getPanel().stopSpinnerIfActive();
        saveSessionIfPersistable();
        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Organizing memories..." + AnsiTheme.RESET + "\n");
        SessionCloseExtractor.onSessionClose(agent, workspace, memoryEnabled, dailyLogsEnabled, switchSessionId);
        session.close();
        ScriptHookLifecycle.fireSessionStopHooks(workspace);
        return switchSessionId.get();
    }

    public void runPrompt(String prompt) {
        try (var lock = acquireLock()) {
            if (lock == null) return;
            runPromptLocked(prompt);
        }
    }

    private void runPromptLocked(String prompt) {
        if (!loadSession()) return;
        recorder = createRecorder();
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore, false, recorder);
        var listener = new CliEventListener(ui, session, agent);
        session.onEvent(recorder);
        session.onEvent(listener);
        setupCompressionListener(listener);
        printBanner();
        printSessionHistory();
        if (prompt != null && !prompt.isBlank()) {
            ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET + prompt.strip() + "\n");
        }
        if (timeLimitSeconds != null && timeLimitSeconds > 0) {
            agent.getExecutionContext().getCustomVariables().put("time_limit_seconds", timeLimitSeconds);
        }
        var timeoutMillis = resolvePromptTimeoutMillis();
        if (timeoutMillis != null) {
            if (timeoutMillis <= 0) {
                LOGGER.info("Run budget expired before the prompt was sent");
                ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "[Cancelled] The run budget is exhausted." + AnsiTheme.RESET + "\n");
            } else {
                LOGGER.info("Agent time limit: {}ms", timeoutMillis);
                runPromptWithTimeLimit(listener, session, prompt, timeoutMillis);
            }
        } else {
            SessionTurnSender.sendPrompt(listener, session, prompt, ui);
        }
        awaitBackgroundWork(session);
        if (memoryEnabled && promptExtractionEnabled && shouldExtractMemory()) {
            MemoryTriggerService.getInstance(workspace).runIncrementalExtractionAndWait();
        }
        listener.getPanel().stopSpinnerIfActive();
        saveSessionIfPersistable();
        session.close();
        ScriptHookLifecycle.fireSessionStopHooks(workspace);
    }

    private SessionLock acquireLock() {
        var acquire = SessionLock.tryAcquire(sessionsDir, sessionId, "cli");
        if (acquire instanceof SessionLock.AcquireResult.Busy busy) {
            var holder = busy.holder() == null ? "another process" : busy.holder().describe();
            ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "Session is in use by " + holder + "."
                    + AnsiTheme.RESET + "\n");
            return null;
        }
        return ((SessionLock.AcquireResult.Acquired) acquire).lock();
    }

    private TranscriptRecorder createRecorder() {
        var store = TranscriptStore.open(sessionsDir, sessionId);
        var metadata = new TranscriptMetadata(workspace.toString(), VersionUtil.getCurrentVersion(), "cli",
            agent::getModel, this::thinkingLevel);
        var transcript = new TranscriptRecorder(store, metadata, () -> agent.getMessages().size(), record -> {
        });
        var seed = transcript.sessionStart(agent.getMessages());
        agent.seedTokenUsage(seed.toUsage(), seed.costUsd());
        return transcript;
    }

    private String thinkingLevel() {
        var effort = AgentSessionRunnerHelper.loadReasoningEffortFromExtraBody();
        return effort == null ? "off" : effort.name().toLowerCase(Locale.ROOT);
    }

    private boolean loadSession() {
        if (!agent.hasPersistenceProvider()) return true;
        var load = SessionLoads.load(sessionPersistence, sessionId);
        switch (load) {
            case SessionLoad.Ok ok -> agent.restore(sessionId, ok.domain());
            case SessionLoad.Missing ignored -> {
            }
            case SessionLoad.Corrupt corrupt -> {
                ui.printStreamingChunk("\n  " + AnsiTheme.ERROR + "Session file is corrupted: " + sessionId
                        + " (" + corrupt.reason() + ")." + AnsiTheme.RESET + "\n");
                return false;
            }
            case SessionLoad.NewerVersion newer -> {
                ui.printStreamingChunk("\n  " + AnsiTheme.ERROR + "Session was written by a newer core-ai version: "
                        + sessionId + " (version " + newer.version() + "). Upgrade core-ai first." + AnsiTheme.RESET + "\n");
                return false;
            }
            case SessionLoad.IoError error -> {
                ui.printStreamingChunk("\n  " + AnsiTheme.ERROR + "Failed to read session " + sessionId + ": "
                        + error.cause().getMessage() + AnsiTheme.RESET + "\n");
                return false;
            }
        }
        return true;
    }

    private void saveSessionIfPersistable() {
        TurnPersistence.save(agent, sessionId, LOGGER, "session close");
    }

    /**
     * One-shot mode must not exit while work launched during the turn is still running. Background
     * task completion is queued as a notification turn, so waiting here both keeps the process
     * alive and lets the turn driver deliver the result to the model instead of dropping it at JVM
     * exit. The wait is bounded so a stuck task can never hang the CLI forever.
     */
    private void awaitBackgroundWork(InProcessAgentSession session) {
        if (backgroundWaitSeconds <= 0 || !session.hasPendingWork()) return;
        var deadline = System.currentTimeMillis() + backgroundWaitSeconds * 1000L;
        var announced = false;
        while (session.hasPendingWork()) {
            if (!announced) {
                ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Waiting for background tasks..." + AnsiTheme.RESET + "\n");
                announced = true;
            }
            if (System.currentTimeMillis() >= deadline) {
                LOGGER.warn("background wait timed out after {}s, cancelling remaining tasks", backgroundWaitSeconds);
                ui.printStreamingChunk("  " + AnsiTheme.WARNING
                        + "Background tasks are still running; cancelling them before exit." + AnsiTheme.RESET + "\n");
                session.cancelBackgroundTasks();
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                session.cancelBackgroundTasks();
                return;
            }
        }
    }

    private Long resolvePromptTimeoutMillis() {
        return promptTimeoutMillis(runBudget, timeLimitSeconds);
    }

    private boolean shouldExtractMemory() {
        if (runBudget == null || runBudget.tokens() == null) return true;
        long reserve = Math.max(10_000L, runBudget.tokens().limitTokens() / 10);
        return runBudget.tokens().remainingTokens() > reserve;
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("HES_LOCAL_EXECUTOR_SERVICE")
    private void runPromptWithTimeLimit(CliEventListener listener, InProcessAgentSession session,
                                         String prompt, long timeoutMillis) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "prompt-time-limit");
            t.setDaemon(true);
            return t;
        });
        Future<?> future = executor.submit(() -> {
            SessionTurnSender.sendPrompt(listener, session, prompt, ui);
            return null;
        });
        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOGGER.info("Time limit ({}ms) reached", timeoutMillis);
            future.cancel(true);
            session.cancelTurn();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            session.cancelTurn();
        } catch (ExecutionException e) {
            LOGGER.error("Agent execution failed", e.getCause());
        } finally {
            executor.shutdownNow();
            listener.getPanel().stopSpinnerIfActive();
        }
    }

    private void printBanner() {
        BannerPrinter.print(ui.getWriter(), modelName, VersionUtil.getCurrentVersion(), null);
        LOGGER.debug("terminal: type={}, jline={}, ansi={}", ui.getTerminalType(), ui.isJLineEnabled(), ui.isAnsiSupported());
    }

    private void printSessionHistory() {
        var printer = new TranscriptHistoryPrinter(ui);
        if (printer.print(recorder, sessionId)) return;
        printer.printAgentHistory(agent, sessionId);
    }

    private void startScheduleTicker(BlockingQueue<String> messageQueue) {
        if (scheduledTaskStore == null) return;
        scheduleTicker = new SessionScheduleTicker(scheduledTaskStore, sessionId);
        scheduleTicker.start(messageQueue);
    }

    private void stopScheduleTicker() {
        if (scheduleTicker != null) {
            scheduleTicker.stop();
            scheduleTicker = null;
        }
    }

    private void readInputLoop(BlockingQueue<String> queue, Semaphore readyForInput) {
        var sessionHandler = new AgentSessionRunnerCommandHandler(
                ui, agent, sessionId, sessionManager,
                new AgentSessionRunnerCommandHandler.Handlers(modelPicker, memoryCommand, recorder));
        var dispatcher = new CommandDispatcher(
                ui, modelPicker, switchSessionId,
                new HandlerContext(commands, memoryCommand, memoryEnabled), sessionHandler,
                new CommandDispatcher.Config(defaultServerUrl, agent.getExecutionContext().getAgentProfileRegistry(), workspace));
        boolean showFrame = true;
        while (true) {
            try {
                readyForInput.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (showFrame) ui.printInputFrame();
            var input = ui.readInput();
            if (input == null || "/exit".equalsIgnoreCase(input.trim())) {
                queue.offer(POISON_PILL);
                break;
            }
            if (input.isBlank()) {
                showFrame = false;
                readyForInput.release();
                continue;
            }
            var trimmed = input.trim();
            if ("/upgrade".equalsIgnoreCase(trimmed)) {
                showFrame = true;
                boolean exitForUpgrade = upgradeHandler.handleUpgrade();
                if (exitForUpgrade) {
                    queue.offer(POISON_PILL);
                    break;
                }
                readyForInput.release();
                continue;
            }
            if (trimmed.startsWith("/")) {
                dispatcher.dispatch(trimmed, queue);
                showFrame = true;
                if (switchSessionId.get() != null) break;
                readyForInput.release();
                continue;
            }
            showFrame = true;
            String expanded = ui.getPasteBuffer().expand(input);
            queue.offer(FileReferenceExpander.expand(expanded));
        }
    }

    private void setupCompressionListener(CliEventListener listener) {
        var compression = agent.getCompression();
        if (compression == null) return;
        // addListener instead of setListener: the memory trigger registers its own listener during
        // agent build, setListener would silently drop it
        compression.addListener(new CompressionProgressListener(listener.getPanel(), ui));
    }

    public record Config(String modelName, boolean autoApproveAll, String sessionId,
                         SessionManager sessionManager, ToolPermissionStore permissionStore,
                         MdMemoryProvider memory, ModelRegistry modelRegistry,
                         SessionPersistence sessionPersistence,
                         boolean memoryEnabled, boolean dailyLogsEnabled,
                         boolean promptExtractionEnabled, Integer timeLimitSeconds,
                         String defaultServerUrl, ai.core.schedule.ScheduledTaskStore scheduledTaskStore,
                         Integer backgroundWaitSeconds, ai.core.agent.budget.RunBudget runBudget) {
    }
}
