package ai.core.cli.memory;

import ai.core.agent.Agent;
import ai.core.cli.utils.AgentFork;
import ai.core.context.CompressionReport;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.ShellCommandTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

@edu.umd.cs.findbugs.annotations.SuppressFBWarnings("MS_EXPOSE_REP")
public final class MemoryTriggerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryTriggerService.class);

    private static final int EXTRACTION_TURN_TRIGGER = 5;
    private static final int EXTRACTION_IDLE_SECONDS = 180;
    private static final int EXTRACTION_MAX_TURNS = 20;
    private static final int LOCK_PROCESSING_MAX_TURNS = 15;
    private static final int IDLE_CHECK_INTERVAL_SECONDS = 30;
    private static final float EXTRACTION_TEMPERATURE = 0.3f;

    private static final ThreadLocal<Boolean> EXTRACTION_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile ZoneId timezone = ZoneId.systemDefault();


    private static volatile MemoryTriggerService instance;

    public static void setTimezone(ZoneId zone) {
        timezone = zone;
    }

    public static ZoneId getTimezone() {
        return timezone;
    }

    public static MemoryTriggerService getInstance() {
        if (instance == null) {
            synchronized (MemoryTriggerService.class) {
                if (instance == null) {
                    instance = new MemoryTriggerService();
                }
            }
        }
        return instance;
    }

    // ---- instance fields ----

    private final Path workspace;
    private final Path dailyLogsDir;
    private final MdMemoryProvider memoryProvider;
    private final MemoryActivityPublisher activity = new MemoryActivityPublisher();
    private final AtomicInteger turnCount = new AtomicInteger(0);
    private final AtomicReference<Instant> lastActivity = new AtomicReference<>(Instant.now());
    private final AtomicBoolean extractionInProgress = new AtomicBoolean(false);
    final AtomicInteger extractionCursor = new AtomicInteger(-1);
    private final AtomicInteger extractionTargetCount = new AtomicInteger(-1);
    private final AtomicReference<CountDownLatch> lockProcessingLatch = new AtomicReference<>();
    private final AtomicBoolean agentBusy = new AtomicBoolean(false);
    private volatile String pendingExplicitRequest;
    private volatile ScheduledExecutorService scheduler;
    private volatile Agent mainAgent;
    private volatile boolean dailyLogsEnabled = false;

    private MemoryTriggerService() {
        this.workspace = Path.of("");
        this.dailyLogsDir = workspace.resolve(".core-ai/daily-logs");
        this.memoryProvider = new MdMemoryProvider(workspace);
    }

    public void setDailyLogsEnabled(boolean enabled) {
        this.dailyLogsEnabled = enabled;
        MemoryExtractionTool.setDirectMode(!enabled);
    }

    public void addActivityListener(MemoryActivityListener listener) {
        activity.addListener(listener);
    }

    // ---- public instance methods ----

    public void init(Agent agent) {
        this.mainAgent = agent;
        // every build creates a fresh agent (and compression), so the listener must be attached per
        // agent, not only for the first one
        attachCompressionListener();
        if (this.scheduler != null) return;

        if (dailyLogsEnabled) {
            ensureDirectories();
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "memory-trigger");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::checkIdleTrigger, IDLE_CHECK_INTERVAL_SECONDS,
                IDLE_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // Async: don't block banner display for new sessions
        var latch = new CountDownLatch(1);
        lockProcessingLatch.set(latch);
        scheduler.execute(() -> {
            try {
                KnowledgeLogTool.pruneOldEntries(workspace);
                processLockFiles();
            } finally {
                latch.countDown();
            }
        });

        LOGGER.debug("MemoryTriggerService initialized");
    }

    public void ensureDirectories() {
        try {
            for (var dir : List.of(".core-ai/daily-logs", ".core-ai/episodes",
                    ".core-ai/knowledge/project", ".core-ai/knowledge/user",
                    ".core-ai/knowledge/feedback", ".core-ai/knowledge/reference")) {
                Files.createDirectories(workspace.resolve(dir));
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to create memory directories: {}", e.getMessage());
        }
    }

    /**
     * Delete knowledge wiki pages and MEMORY.md, then recreate the directory structure.
     * Preserves daily-logs, episodes, and log.md.
     * Also removes legacy paths from older memory versions.
     */
    public void clearKnowledge() {
        var knowledgeDir = workspace.resolve(".core-ai/knowledge");
        try {
            if (Files.exists(knowledgeDir)) {
                MemoryFiles.deleteRecursive(knowledgeDir);
            }
            Files.deleteIfExists(workspace.resolve(".core-ai/MEMORY.md"));
            MemoryFiles.deleteRecursive(workspace.resolve(".core-ai/memory"));
            ensureDirectories();
            Files.createFile(workspace.resolve(".core-ai/knowledge/MEMORY.md"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void resetCursorToEnd() {
        extractionCursor.set(Math.max(0, mainAgent.getHistory().size()));
    }

    /**
     * Blocks until the most recent lock processing cycle completes.
     * Used on session resume to avoid mid-session system prompt updates
     * invalidating KV cache.
     */
    public void awaitLockProcessing() {
        var latch = lockProcessingLatch.get();
        if (latch != null) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isLockProcessingPending() {
        var latch = lockProcessingLatch.get();
        return latch != null && latch.getCount() > 0;
    }

    public int getTurnCount() {
        return turnCount.get();
    }

    public void runIncrementalExtractionAndWait() {
        if (!extractionInProgress.compareAndSet(false, true)) {
            LOGGER.debug("Extraction already in progress, waiting for completion");
            while (extractionInProgress.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            return;
        }
        try {
            runExtractionAgent(MemoryExtractionReport.Trigger.PROMPT);
        } finally {
            extractionInProgress.set(false);
        }
    }

    /**
     * Runs the extraction agent synchronously and guarantees it covers the current conversation
     * (including the message that triggered the call). Used by {@link ExtractMemoryNowTool} so an
     * explicit "remember" request is persisted BEFORE the main agent continues with the operation.
     *
     * @param explicitRequest the fact the user asked to remember, or {@code null} for a plain run
     */
    public void runExplicitMemoryExtraction(String explicitRequest) {
        if (isExtractionRunningOnCurrentThread()) return; // re-entrant call from inside an extraction run
        while (true) {
            waitForExtractionIdle();
            if (extractionInProgress.compareAndSet(false, true)) break;
        }
        try {
            pendingExplicitRequest = explicitRequest;
            runExtractionAgent(MemoryExtractionReport.Trigger.EXPLICIT);
        } finally {
            pendingExplicitRequest = null;
            extractionInProgress.set(false);
        }
    }

    boolean isExtractionRunningOnCurrentThread() {
        return Boolean.TRUE.equals(EXTRACTION_THREAD.get());
    }

    private void waitForExtractionIdle() {
        while (extractionInProgress.get()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // keep waiting: an explicit memory request must be persisted before the turn continues
                LOGGER.warn("Interrupted while waiting for extraction to finish: {}", e.getMessage());
            }
        }
    }

    public void onAgentStart() {
        agentBusy.set(true);
    }

    public void onAgentEnd() {
        agentBusy.set(false);
        lastActivity.set(Instant.now());
    }

    public void onTurnComplete() {
        lastActivity.set(Instant.now());
        if (extractionInProgress.get()) return;
        int current = turnCount.incrementAndGet();
        if (current >= EXTRACTION_TURN_TRIGGER) {
            LOGGER.debug("Turn trigger: {} turns reached", current);
            scheduler.execute(() -> runIncrementalExtraction(MemoryExtractionReport.Trigger.TURN));
            turnCount.set(0);
        }
    }

    public void onUserActivity() {
        lastActivity.set(Instant.now());
    }

    public List<ToolCall> buildMemoryTools(Agent agent) {
        var tools = new ArrayList<ToolCall>();
        tools.add(MemoryExtractionTool.builder().build());
        tools.add(KnowledgeLogTool.addBuilder().workspace(workspace).build());
        tools.add(ExtractionCursorTool.readBuilder()
                .cursorReader(extractionCursor::get)
                .totalMessagesSnapshot(agent.getHistory()::size)
                .build());
        var liveHistory = agent.getHistory();
        IntSupplier totalSnapshot = () -> {
            int captured = extractionTargetCount.get();
            return captured >= 0 ? captured : liveHistory.size();
        };
        tools.add(ExtractionCursorTool.advanceBuilder()
                .cursorWriter(extractionCursor::set)
                .totalMessagesSnapshot(totalSnapshot)
                .build());
        return tools;
    }

    // ---- private instance methods ----

    private int readCursor() {
        int cursor = extractionCursor.get();
        if (cursor >= 0 && cursor >= mainAgent.getHistory().size()) {
            // legacy cursor values were based on the compressed message list — treat
            // everything as already extracted instead of re-extracting from scratch
            LOGGER.debug("Cursor {} stale, resetting to history size {}", cursor, mainAgent.getHistory().size());
            extractionCursor.set(mainAgent.getHistory().size());
            return mainAgent.getHistory().size();
        }
        return cursor;
    }

    private void attachCompressionListener() {
        var compression = mainAgent.getCompression();
        if (compression != null) {
            compression.addListener(report -> {
                if (report.completed()) {
                    // history is append-only — compression does not move the extraction cursor
                    MemorySectionManager.reloadAgentMemorySection(mainAgent, memoryProvider);
                } else if (scheduler != null && report.phase() == CompressionReport.Phase.STARTED && !extractionInProgress.get()) {
                    scheduler.execute(() -> runIncrementalExtraction(MemoryExtractionReport.Trigger.COMPRESSION));
                }
            });
        }
    }

    private void processLockFiles() {
        if (!dailyLogsEnabled) return;
        try {
            List<Path> lockFiles = findLockFiles();
            if (lockFiles.isEmpty()) return;

            for (Path lockFile : lockFiles) {
                runLockProcessingAgent(lockFile);
            }
            MemorySectionManager.reloadAgentMemorySection(mainAgent, memoryProvider);
        } catch (Exception e) {
            LOGGER.warn("Startup lock processing failed: {}", e.getMessage());
        }
    }

    private void runLockProcessingAgent(Path lockFile) {
        try {
            String lockContent = Files.readString(lockFile).strip();
            if (lockContent.isBlank()) {
                return;
            }
            String userPrompt = LockProcessingPrompt.format(lockFile, lockContent,
                    workspace, ZonedDateTime.now(timezone), LOCK_PROCESSING_MAX_TURNS);

            var tools = new ArrayList<>(BuiltinTools.FILE_OPERATIONS);
            tools.add(ShellCommandTool.builder().build());
            tools.add(MemoryExtractionTool.builder().build());
            tools.add(KnowledgeLogTool.addBuilder().workspace(workspace).build());
            var agent = AgentFork.forkConfigOnly(mainAgent, new AgentFork.ForkConfig("lock", LOCK_PROCESSING_MAX_TURNS,
                    (double) EXTRACTION_TEMPERATURE, false, null, tools));
            agent.injectUserMessage(userPrompt);
            agent.continueWithInjectedMessage();
        } catch (Exception e) {
            LOGGER.warn("Lock agent failed: {}", e.getMessage());
        }
    }

    private void checkIdleTrigger() {
        if (agentBusy.get()) return;

        long idleSeconds = Instant.now().getEpochSecond() - lastActivity.get().getEpochSecond();
        if (idleSeconds >= EXTRACTION_IDLE_SECONDS && !extractionInProgress.get() && turnCount.get() > 2) {
            LOGGER.debug("Idle trigger: {}s without activity", idleSeconds);
            runIncrementalExtraction(MemoryExtractionReport.Trigger.IDLE);
            turnCount.set(0);
        }
    }

    private void runIncrementalExtraction(MemoryExtractionReport.Trigger trigger) {
        if (!extractionInProgress.compareAndSet(false, true)) {
            LOGGER.debug("Extraction already in progress, skipping");
            return;
        }
        try {
            runExtractionAgent(trigger);
        } finally {
            extractionInProgress.set(false);
        }
    }

    private void runExtractionAgent(MemoryExtractionReport.Trigger trigger) {
        String explicitRequest = pendingExplicitRequest;
        var sourceAgent = mainAgent;
        EXTRACTION_THREAD.set(Boolean.TRUE);
        var observer = new MemoryExtractionObserver();
        String runId = Long.toHexString(System.nanoTime());
        long startedAt = System.currentTimeMillis();
        activity.publish(sourceAgent, new MemoryExtractionReport(runId, MemoryExtractionReport.Phase.STARTED, trigger,
                0, -1, List.of(), List.of(), null));
        try {
            int cursor = readCursor();
            int totalMessages = sourceAgent.getHistory().size();
            extractionTargetCount.set(totalMessages);
            var agent = AgentFork.fork(sourceAgent, new AgentFork.ForkConfig("extraction", EXTRACTION_MAX_TURNS, (double) EXTRACTION_TEMPERATURE, false, null));
            agent.addLifecycle(observer);
            agent.injectUserMessage(buildExtractionPrompt(cursor, totalMessages, EXTRACTION_MAX_TURNS, explicitRequest));
            agent.continueWithInjectedMessage();
        } catch (Exception e) {
            LOGGER.warn("{} agent failed: {}", "extraction", e.getMessage());
        } finally {
            pendingExplicitRequest = null;
            extractionTargetCount.set(-1);
            EXTRACTION_THREAD.remove();
            activity.publish(sourceAgent, new MemoryExtractionReport(runId, MemoryExtractionReport.Phase.COMPLETED, trigger,
                    System.currentTimeMillis() - startedAt, extractionCursor.get(), observer.added(), observer.updated(), observer.note()));
        }
    }

    private String buildExtractionPrompt(int cursor, int totalMessages, int maxTurns, String explicitRequest) {
        String cursorInfo = cursor >= 0
                ? "Messages 0–" + (cursor - 1) + " have been extracted (cursor=" + cursor + ", total=" + totalMessages + ")."
                : "No messages have been extracted yet (total=" + totalMessages + ").";
        return ExtractionPrompt.format(workspace, cursorInfo, ZonedDateTime.now(timezone), maxTurns, explicitRequest);
    }

    private List<Path> findLockFiles() {
        if (!Files.isDirectory(dailyLogsDir)) return List.of();
        try (Stream<Path> stream = Files.list(dailyLogsDir)) {
            return stream
                    .filter(MemoryFiles::isLockFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOGGER.warn("Failed to list lock files: {}", e.getMessage());
            return List.of();
        }
    }
}
