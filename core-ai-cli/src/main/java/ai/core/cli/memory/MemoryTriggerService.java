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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** One process serves exactly one workspace: the first caller wins. */
    public static MemoryTriggerService getInstance(Path workspace) {
        if (instance == null) {
            synchronized (MemoryTriggerService.class) {
                if (instance == null) {
                    instance = new MemoryTriggerService(workspace);
                }
            }
        }
        return instance;
    }

    private final Path workspace;
    private final Path dailyLogsDir;
    private final MdMemoryProvider memoryProvider;
    private final MemoryMaintenanceService maintenance;
    private final MemoryActivityPublisher activity = new MemoryActivityPublisher();
    private final Map<Agent, AgentMemoryState> agentStates = Collections.synchronizedMap(new WeakHashMap<>());
    private final AtomicBoolean extractionInProgress = new AtomicBoolean(false);
    private final AtomicReference<CountDownLatch> lockProcessingLatch = new AtomicReference<>();
    private volatile ScheduledExecutorService scheduler;
    private volatile Agent mainAgent;
    private volatile boolean dailyLogsEnabled = false;

    private MemoryTriggerService(Path workspace) {
        this.workspace = workspace.toAbsolutePath();
        this.dailyLogsDir = this.workspace.resolve(".core-ai/daily-logs");
        this.memoryProvider = new MdMemoryProvider(this.workspace);
        this.maintenance = new MemoryMaintenanceService(this.workspace, this.memoryProvider, () -> this.mainAgent);
    }

    public void setDailyLogsEnabled(boolean enabled) {
        this.dailyLogsEnabled = enabled;
        MemoryExtractionTool.setDirectMode(!enabled);
    }

    public void addActivityListener(MemoryActivityListener listener) {
        activity.addListener(listener);
    }

    public void init(Agent agent) {
        this.mainAgent = agent;
        // every build creates a fresh agent (and compression), so the listener must be attached per
        // agent, not only for the first one
        attachCompressionListener(agent);
        if (this.scheduler != null) return;

        if (dailyLogsEnabled) ensureDirectories();

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
                maintenance.run();
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
     * daily-logs, episodes, log.md and the global user memory are preserved.
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

    public void resetCursorToEnd(Agent agent) {
        state(agent).extractionCursor.set(Math.max(0, agent.getHistory().size()));
    }

    public MemoryMaintenanceService maintenance() {
        return maintenance;
    }

    /** Blocks until the most recent lock processing cycle completes, so a resume does not invalidate the KV cache. */
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

    public int getTurnCount(Agent agent) {
        return state(agent).turnCount.get();
    }

    int extractionCursor(Agent agent) {
        return state(agent).extractionCursor.get();
    }

    public void runIncrementalExtractionAndWait(Agent sourceAgent) {
        while (true) {
            waitForExtractionIdle();
            if (extractionInProgress.compareAndSet(false, true)) break;
        }
        try {
            runExtractionAgent(sourceAgent, MemoryExtractionReport.Trigger.PROMPT, null);
        } finally {
            extractionInProgress.set(false);
        }
    }

    /**
     * Runs the extraction agent synchronously for the conversation of {@code sourceAgent}, and reports
     * the activity to that same agent's session. Used by {@link ExtractMemoryNowTool} so an explicit
     * "remember" request is persisted before the main agent continues.
     */
    public int runExplicitMemoryExtraction(Agent sourceAgent, String explicitRequest) {
        if (isExtractionRunningOnCurrentThread()) return state(sourceAgent).extractionCursor.get(); // re-entrant call from inside an extraction run
        while (true) {
            waitForExtractionIdle();
            if (extractionInProgress.compareAndSet(false, true)) break;
        }
        try {
            return runExtractionAgent(sourceAgent, MemoryExtractionReport.Trigger.EXPLICIT, explicitRequest);
        } finally {
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

    public void onAgentStart(Agent agent) {
        state(agent).agentBusy.set(true);
    }

    public void onAgentEnd(Agent agent) {
        var state = state(agent);
        state.agentBusy.set(false);
        state.lastActivity.set(Instant.now());
    }

    public void onTurnComplete(Agent agent) {
        var state = state(agent);
        state.lastActivity.set(Instant.now());
        if (extractionInProgress.get()) return;
        int current = state.turnCount.incrementAndGet();
        if (current >= EXTRACTION_TURN_TRIGGER) {
            LOGGER.debug("Turn trigger: {} turns reached", current);
            scheduler.execute(() -> runIncrementalExtraction(agent, MemoryExtractionReport.Trigger.TURN));
            state.turnCount.set(0);
        }
    }

    public void onUserActivity(Agent agent) {
        state(agent).lastActivity.set(Instant.now());
    }

    public List<ToolCall> buildMemoryTools(Agent agent) {
        var state = state(agent);
        var tools = new ArrayList<ToolCall>();
        tools.add(MemoryExtractionTool.builder().build());
        tools.add(SaveUserMemoryTool.builder().globalMemory(memoryProvider.getGlobalMemory()).build());
        tools.add(KnowledgeLogTool.addBuilder().workspace(workspace).build());
        tools.add(ExtractionCursorTool.readBuilder()
                .cursorReader(state.extractionCursor::get)
                .totalMessagesSnapshot(agent.getHistory()::size)
                .build());
        IntSupplier totalSnapshot = () -> {
            int captured = state.extractionTargetCount.get();
            return captured >= 0 ? captured : agent.getHistory().size();
        };
        tools.add(ExtractionCursorTool.advanceBuilder()
                .cursorWriter(state.extractionCursor::set)
                .totalMessagesSnapshot(totalSnapshot)
                .build());
        return tools;
    }

    private AgentMemoryState state(Agent agent) {
        synchronized (agentStates) {
            return agentStates.computeIfAbsent(agent, ignored -> new AgentMemoryState());
        }
    }

    private List<Agent> liveAgents() {
        synchronized (agentStates) {
            return new ArrayList<>(agentStates.keySet());
        }
    }

    private int readCursor(Agent agent, AgentMemoryState state) {
        int cursor = state.extractionCursor.get();
        if (cursor >= 0 && cursor >= agent.getHistory().size()) {
            // legacy cursor values were based on the compressed message list — treat
            // everything as already extracted instead of re-extracting from scratch
            LOGGER.debug("Cursor {} stale, resetting to history size {}", cursor, agent.getHistory().size());
            state.extractionCursor.set(agent.getHistory().size());
            return agent.getHistory().size();
        }
        return cursor;
    }

    private void attachCompressionListener(Agent agent) {
        var state = state(agent);
        if (!state.compressionListenerAttached.compareAndSet(false, true)) return;
        var compression = agent.getCompression();
        if (compression != null) {
            compression.addListener(report -> {
                if (report.completed()) {
                    // history is append-only — compression does not move the extraction cursor
                    MemorySectionManager.reloadAgentMemorySection(agent, memoryProvider);
                } else if (scheduler != null && report.phase() == CompressionReport.Phase.STARTED && !extractionInProgress.get()) {
                    scheduler.execute(() -> runIncrementalExtraction(agent, MemoryExtractionReport.Trigger.COMPRESSION));
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
            var agent = mainAgent;
            if (agent != null) MemorySectionManager.reloadAgentMemorySection(agent, memoryProvider);
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
            tools.add(SaveUserMemoryTool.builder().globalMemory(memoryProvider.getGlobalMemory()).build());
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
        if (!extractionInProgress.get()) maintenance.runDailyIfDue();

        for (Agent agent : liveAgents()) {
            var state = state(agent);
            if (state.agentBusy.get()) continue;
            long idleSeconds = Instant.now().getEpochSecond() - state.lastActivity.get().getEpochSecond();
            if (idleSeconds < EXTRACTION_IDLE_SECONDS) continue;
            if (!extractionInProgress.get() && state.turnCount.get() > 2) {
                LOGGER.debug("Idle trigger: {}s without activity", idleSeconds);
                state.turnCount.set(0);
                runIncrementalExtraction(agent, MemoryExtractionReport.Trigger.IDLE);
            }
        }
    }

    private void runIncrementalExtraction(Agent agent, MemoryExtractionReport.Trigger trigger) {
        if (!extractionInProgress.compareAndSet(false, true)) {
            LOGGER.debug("Extraction already in progress, skipping");
            return;
        }
        try {
            runExtractionAgent(agent, trigger, null);
        } finally {
            extractionInProgress.set(false);
        }
    }

    private int runExtractionAgent(Agent sourceAgent, MemoryExtractionReport.Trigger trigger, String explicitRequest) {
        if (sourceAgent == null) {
            LOGGER.warn("Memory extraction skipped: no source agent");
            return -1;
        }
        var state = state(sourceAgent);
        EXTRACTION_THREAD.set(Boolean.TRUE);
        var observer = new MemoryExtractionObserver();
        String runId = Long.toHexString(System.nanoTime());
        long startedAt = System.currentTimeMillis();
        activity.publish(sourceAgent, new MemoryExtractionReport(runId, MemoryExtractionReport.Phase.STARTED, trigger,
                0, -1, List.of(), List.of(), null));
        try {
            int cursor = readCursor(sourceAgent, state);
            int totalMessages = sourceAgent.getHistory().size();
            state.extractionTargetCount.set(totalMessages);
            var agent = AgentFork.fork(sourceAgent, new AgentFork.ForkConfig("extraction", EXTRACTION_MAX_TURNS, (double) EXTRACTION_TEMPERATURE, false, null));
            agent.addLifecycle(observer);
            agent.injectUserMessage(buildExtractionPrompt(cursor, totalMessages, EXTRACTION_MAX_TURNS, explicitRequest));
            agent.continueWithInjectedMessage();
        } catch (Exception e) {
            LOGGER.warn("{} agent failed: {}", "extraction", e.getMessage());
        } finally {
            state.extractionTargetCount.set(-1);
            EXTRACTION_THREAD.remove();
            activity.publish(sourceAgent, new MemoryExtractionReport(runId, MemoryExtractionReport.Phase.COMPLETED, trigger,
                    System.currentTimeMillis() - startedAt, state.extractionCursor.get(), observer.added(), observer.updated(), observer.note()));
        }
        return state.extractionCursor.get();
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
