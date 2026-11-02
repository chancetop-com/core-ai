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
import ai.core.cli.ui.StreamingMarkdownRenderer;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.upgrade.VersionUtil;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.RoleType;
import ai.core.schedule.CronExpression;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionLoad;
import ai.core.session.SessionLoads;
import ai.core.session.SessionLock;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence;
import ai.core.session.ToolPermissionStore;
import ai.core.session.TurnPersistence;
import ai.core.session.transcript.TranscriptKinds;
import ai.core.session.transcript.TranscriptMetadata;
import ai.core.session.transcript.TranscriptReader;
import ai.core.session.transcript.TranscriptRecord;
import ai.core.session.transcript.TranscriptRecorder;
import ai.core.session.transcript.TranscriptStore;
import ai.core.tool.tools.ScheduledTaskTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private java.util.concurrent.ScheduledExecutorService scheduleTicker;

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
        var lock = acquireLock();
        if (lock == null) return null;
        try {
            return runLocked();
        } finally {
            lock.close();
        }
    }

    private String runLocked() {
        if (!loadSession()) return null;
        if (agent.hasPersistenceProvider() && memoryEnabled && !agent.getHistory().isEmpty()) {
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
        startSenderThread(messageQueue, listener, session, readyForInput);
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
        var lock = acquireLock();
        if (lock == null) return;
        try {
            runPromptLocked(prompt);
        } finally {
            lock.close();
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
            sendPrompt(listener, session, prompt);
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
            sendPrompt(listener, session, prompt);
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
        if (printTranscriptHistory()) return;
        printAgentHistory();
    }

    /**
     * Restores the last turns from the transcript, so a resumed session shows thinking, tool calls and
     * notices — not just the text the agent history kept. Falls back to history when no record exists.
     */
    private boolean printTranscriptHistory() {
        if (recorder == null) return false;
        var store = recorder.store();
        var reader = new TranscriptReader(store);
        var records = new ArrayList<TranscriptRecord>();
        long before = store.maxSeq() + 1;
        boolean truncated = false;
        for (int pages = 0; pages < 100; pages++) {
            var page = reader.page(before, 100, 2000);
            if (page.records().isEmpty()) break;
            records.addAll(0, page.records());
            if (page.nextBeforeSeq() == null) break;
            before = page.nextBeforeSeq();
            if (pages == 99) truncated = true;
        }
        if (records.isEmpty()) return false;
        var renderer = new StreamingMarkdownRenderer(ui.getWriter(), ui.isAnsiSupported(), ui::getTerminalWidth);
        var cancelledSteers = cancelledSteerSeqs(records);
        boolean printed = false;
        for (var record : records) {
            if (record.kind == null) continue;
            switch (record.kind) {
                case TranscriptKinds.TURN_START -> printed |= printTurnInputs(record);
                case TranscriptKinds.STEER -> {
                    if (TranscriptKinds.STEER_PENDING.equals(record.text("state"))
                        && !cancelledSteers.contains(record.seq)) {
                        printed |= printPrompt(recordText(record, "text"));
                    }
                }
                case TranscriptKinds.ASSISTANT_TEXT -> {
                    var text = recordText(record, "text");
                    if (text != null && !text.isBlank()) {
                        printAssistantText(renderer, text);
                        printed = true;
                    }
                }
                case TranscriptKinds.REASONING -> {
                    var text = recordText(record, "reasoning");
                    if (text != null && !text.isBlank()) {
                        printReasoning(text);
                        printed = true;
                    }
                }
                case TranscriptKinds.TOOL_START -> {
                    ui.printStreamingChunk("\n  " + AnsiTheme.SEPARATOR + "●" + AnsiTheme.RESET + ' '
                        + record.text("toolName") + "\n");
                    printPreview(record, "arguments");
                    printed = true;
                }
                case TranscriptKinds.TOOL_RESULT -> {
                    var status = record.text("status");
                    var color = "success".equals(status) ? AnsiTheme.SEPARATOR : AnsiTheme.ERROR;
                    ui.printStreamingChunk("  " + color + (status == null ? "done" : status) + AnsiTheme.RESET + "\n");
                    printPreview(record, "result");
                    printed = true;
                }
                case TranscriptKinds.NOTICE, TranscriptKinds.COMPRESSION, TranscriptKinds.SANDBOX -> {
                    var text = firstText(record, "text", "message");
                    if (text != null && !text.isBlank()) {
                        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + text.strip() + AnsiTheme.RESET + "\n");
                        printed = true;
                    }
                }
                case TranscriptKinds.ERROR -> {
                    var text = recordText(record, "message");
                    if (text != null && !text.isBlank()) {
                        ui.printStreamingChunk("\n  " + AnsiTheme.ERROR + text.strip() + AnsiTheme.RESET + "\n");
                        printed = true;
                    }
                }
                case TranscriptKinds.TURN_COMPLETE -> {
                    var summary = turnSummary(record);
                    if (summary != null) {
                        ui.printStreamingChunk("  " + AnsiTheme.MUTED + summary + AnsiTheme.RESET + "\n");
                    }
                }
                default -> {
                }
            }
        }
        if (truncated) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  … earlier turns are in the transcript" + AnsiTheme.RESET + "\n");
        }
        if (printed) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  ↑ restored " + sessionId + AnsiTheme.RESET + "\n");
        }
        return printed;
    }

    private Set<Long> cancelledSteerSeqs(List<TranscriptRecord> records) {
        var cancelled = new HashSet<Long>();
        for (var record : records) {
            if (!TranscriptKinds.STEER.equals(record.kind)) continue;
            if (!TranscriptKinds.STEER_CANCELLED.equals(record.text("state"))) continue;
            var ref = record.number("ref_seq");
            if (ref != null) cancelled.add(ref);
        }
        return cancelled;
    }

    private boolean printTurnInputs(TranscriptRecord record) {
        var inputs = record.objects("inputs");
        if (inputs == null) return false;
        var source = record.text("source");
        boolean printed = false;
        for (var input : inputs) {
            var value = input.get("text");
            if (value == null) value = input.get("text_preview");
            if (value == null) continue;
            var prefix = TranscriptKinds.SOURCE_TASK_NOTE.equals(source)
                ? AnsiTheme.MUTED + "[task] " + AnsiTheme.RESET : "";
            printed |= printPrompt(prefix + value);
        }
        return printed;
    }

    private boolean printPrompt(String text) {
        if (text == null || text.isBlank()) return false;
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET + text.strip() + "\n");
        return true;
    }

    private void printAssistantText(StreamingMarkdownRenderer renderer, String text) {
        var writer = ui.getWriter();
        writer.print("\n" + AnsiTheme.SEPARATOR + "●" + AnsiTheme.RESET + " ");
        writer.flush();
        renderer.setFirstLineOffset(AnsiTheme.displayWidth("● "));
        renderer.processChunk(text.replace("\n", "\n  "));
        renderer.flush();
        renderer.reset();
        writer.println();
    }

    private void printReasoning(String text) {
        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "◌ thinking" + AnsiTheme.RESET + "\n");
        var body = text.strip();
        if (body.length() > 4000) body = body.substring(0, 4000) + "…";
        for (var line : body.split("\n")) {
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + line + AnsiTheme.RESET + "\n");
        }
    }

    private void printPreview(TranscriptRecord record, String field) {
        var text = recordText(record, field);
        if (text == null || text.isBlank()) return;
        var line = text.strip().split("\n", 2)[0];
        if (line.length() > 160) line = line.substring(0, 160) + "…";
        ui.printStreamingChunk("    " + AnsiTheme.MUTED + line + AnsiTheme.RESET + "\n");
    }

    private String turnSummary(TranscriptRecord record) {
        var parts = new java.util.ArrayList<String>();
        var duration = record.number("duration_ms");
        if (duration != null) parts.add(String.format(Locale.ROOT, "%.1fs", duration / 1000.0));
        var input = record.number("input_tokens");
        var output = record.number("output_tokens");
        if (input != null || output != null) {
            parts.add((input == null ? 0 : input) + " in / " + (output == null ? 0 : output) + " out");
        }
        var cost = record.decimal("cost_usd");
        if (cost != null && cost > 0) parts.add(String.format(Locale.ROOT, "$%.4f", cost));
        return parts.isEmpty() ? null : "✦ " + String.join(" · ", parts);
    }

    private String recordText(TranscriptRecord record, String field) {
        var text = record.text(field);
        return text != null ? text : record.text(field + "_preview");
    }

    private String firstText(TranscriptRecord record, String first, String second) {
        var text = recordText(record, first);
        return text != null ? text : recordText(record, second);
    }

    private void printAgentHistory() {
        var messages = agent.getHistory();
        boolean hasHistory = false;
        var renderer = new StreamingMarkdownRenderer(ui.getWriter(), ui.isAnsiSupported(), ui::getTerminalWidth);
        for (var msg : messages) {
            String text = msg.getTextContent();
            if (text == null || text.isBlank()) continue;
            if (msg.role == RoleType.USER) {
                hasHistory = true;
                ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET + text.strip() + "\n");
            } else if (msg.role == RoleType.ASSISTANT) {
                hasHistory = true;
                // mirror the live turn rendering: ● icon with continuation lines indented
                var writer = ui.getWriter();
                writer.print("\n" + AnsiTheme.SEPARATOR + "\u25CF" + AnsiTheme.RESET + " ");
                writer.flush();
                renderer.setFirstLineOffset(AnsiTheme.displayWidth("\u25CF "));
                renderer.processChunk(text.replace("\n", "\n  "));
                renderer.flush();
                renderer.reset();
                writer.println();
            }
        }
        if (hasHistory) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  ↑ restored " + sessionId + AnsiTheme.RESET + "\n");
        }
    }

    // Fires due session-bound scheduled tasks while the session is open: the trigger
    // message is enqueued through the same queue as user input, so the sender thread
    // renders it exactly like a user turn. Claiming always advances next_run_at, so
    // occurrences missed while the CLI was closed are not caught up.
    private void startScheduleTicker(BlockingQueue<String> messageQueue) {
        if (scheduledTaskStore == null) return;
        scheduleTicker = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "schedule-ticker");
            t.setDaemon(true);
            return t;
        });
        scheduleTicker.scheduleAtFixedRate(() -> tickSchedules(messageQueue), 30, 30, TimeUnit.SECONDS);
    }

    private void tickSchedules(BlockingQueue<String> messageQueue) {
        try {
            var now = ZonedDateTime.now();
            for (var task : scheduledTaskStore.findDue(now)) {
                if (!task.sessionId.equals(sessionId)) continue;
                var zone = ZoneId.of(task.timezone);
                var nextRunAt = new CronExpression(task.cronExpression).nextAfter(now, zone);
                if (!scheduledTaskStore.claim(task.id, task.nextRunAt, nextRunAt)) continue;
                messageQueue.offer(ScheduledTaskTool.buildTriggerMessage(task));
                LOGGER.info("fired scheduled task, id={}, sessionId={}", task.id, task.sessionId);
            }
        } catch (Exception e) {
            LOGGER.warn("schedule tick failed", e);
        }
    }

    private void stopScheduleTicker() {
        if (scheduleTicker != null) {
            scheduleTicker.shutdownNow();
            scheduleTicker = null;
        }
    }

    private void startSenderThread(BlockingQueue<String> queue, CliEventListener listener, InProcessAgentSession session, Semaphore readyForInput) {
        Thread senderThread = new Thread(() -> {
            try {
                while (true) {
                    String msg = queue.take();
                    if (POISON_PILL.equals(msg)) break;
                    sendMessage(listener, session, readyForInput, msg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "sender-thread");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    private void sendMessage(CliEventListener listener, InProcessAgentSession session, Semaphore readyForInput, String msg) {
        LOGGER.debug("sending message: {}", msg);
        listener.prepareTurn();
        try {
            session.sendMessage(msg);
        } finally {
            listener.getPanel().stopSpinnerIfActive();
        }
        LOGGER.debug("waiting for turn...");
        listener.waitForTurn();
        LOGGER.debug("turn finished");
        readyForInput.release();
    }

    private void sendPrompt(CliEventListener listener, InProcessAgentSession session, String prompt) {
        LOGGER.debug("sending prompt: {}", prompt);
        listener.prepareTurn();
        String expanded = ui.getPasteBuffer().expand(prompt == null ? "" : prompt);
        try {
            session.sendMessage(FileReferenceExpander.expand(expanded));
        } finally {
            listener.getPanel().stopSpinnerIfActive();
        }
        LOGGER.debug("waiting for turn...");
        listener.waitForTurn();
        LOGGER.debug("turn finished");
    }

    private void readInputLoop(BlockingQueue<String> queue, Semaphore readyForInput) {
        var sessionHandler = new AgentSessionRunnerCommandHandler(
                ui, agent, sessionId, sessionManager, modelPicker, memoryCommand, recorder);
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
            if (showFrame) {
                ui.printInputFrame();
            }
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
