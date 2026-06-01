package ai.core.cli.memory;

import ai.core.agent.Agent;
import ai.core.cli.utils.AgentFork;
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
import java.util.Comparator;
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

public final class MemoryTriggerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryTriggerService.class);

    private static final int EXTRACTION_TURN_TRIGGER = 5;
    private static final int EXTRACTION_IDLE_SECONDS = 180;
    private static final int EXTRACTION_MAX_TURNS = 20;
    private static final int LOCK_PROCESSING_MAX_TURNS = 15;
    private static final int IDLE_CHECK_INTERVAL_SECONDS = 30;
    private static final float EXTRACTION_TEMPERATURE = 0.3f;

    private static final String LOCK_SUFFIX = ".lock";

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

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(MemoryTriggerService::tryDelete);
        }
    }

    private static void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            LOGGER.debug("Failed to delete {}: {}", path, ignored.getMessage());
        }
    }

    // ---- instance fields ----

    private final Path workspace;
    private final Path dailyLogsDir;
    private final MdMemoryProvider memoryProvider;
    private final AtomicInteger turnCount = new AtomicInteger(0);
    private final AtomicReference<Instant> lastActivity = new AtomicReference<>(Instant.now());
    private final AtomicBoolean extractionInProgress = new AtomicBoolean(false);
    final AtomicInteger extractionCursor = new AtomicInteger(-1);
    private final AtomicInteger extractionTargetCount = new AtomicInteger(-1);
    private final AtomicReference<CountDownLatch> lockProcessingLatch = new AtomicReference<>();
    private final AtomicBoolean agentBusy = new AtomicBoolean(false);
    private volatile ScheduledExecutorService scheduler;
    private volatile Agent mainAgent;

    private MemoryTriggerService() {
        this.workspace = Path.of("");
        this.dailyLogsDir = workspace.resolve(".core-ai/daily-logs");
        this.memoryProvider = new MdMemoryProvider(workspace);
    }

    // ---- public instance methods ----

    public void init(Agent agent) {
        this.mainAgent = agent;
        if (this.scheduler != null) return;

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

        attachCompressionListener();
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
                deleteRecursive(knowledgeDir);
            }
            Files.deleteIfExists(workspace.resolve(".core-ai/MEMORY.md"));
            deleteRecursive(workspace.resolve(".core-ai/memory"));
            ensureDirectories();
            Files.createFile(workspace.resolve(".core-ai/knowledge/MEMORY.md"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void resetCursorToEnd() {
        extractionCursor.set(Math.max(0, mainAgent.getMessages().size()));
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
            runExtractionAgent();
        } finally {
            extractionInProgress.set(false);
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
            scheduler.execute(this::runIncrementalExtraction);
            turnCount.set(0);
        }
    }

    public void onUserActivity() {
        lastActivity.set(Instant.now());
    }

    public List<ToolCall> buildMemoryTools(Agent agent) {
        var tools = new ArrayList<ToolCall>();
        tools.add(KnowledgeLogTool.addBuilder().workspace(workspace).build());
        tools.add(ExtractionCursorTool.readBuilder()
                .cursorReader(extractionCursor::get)
                .totalMessagesSnapshot(agent.getMessages()::size)
                .build());
        var liveMessages = agent.getMessages();
        IntSupplier totalSnapshot = () -> {
            int captured = extractionTargetCount.get();
            return captured >= 0 ? captured : liveMessages.size();
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
        if (cursor >= 0 && cursor >= mainAgent.getMessages().size()) {
            LOGGER.debug("Cursor {} stale, resetting", cursor);
            extractionCursor.set(-1);
            return -1;
        }
        return cursor;
    }

    private void attachCompressionListener() {
        var compression = mainAgent.getCompression();
        if (compression != null) {
            compression.addListener((beforeCount, afterCount, completed) -> {
                if (completed) {
                    extractionCursor.set(-1);
                    MemorySectionManager.reloadAgentMemorySection(mainAgent, memoryProvider);
                } else if (!extractionInProgress.get() && scheduler != null) {
                    scheduler.execute(this::runIncrementalExtraction);
                }
            });
        }
    }

    private void processLockFiles() {
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
            runIncrementalExtraction();
            turnCount.set(0);
        }
    }

    private void runIncrementalExtraction() {
        if (!extractionInProgress.compareAndSet(false, true)) {
            LOGGER.debug("Extraction already in progress, skipping");
            return;
        }
        try {
            runExtractionAgent();
        } finally {
            extractionInProgress.set(false);
        }
    }

    private void runExtractionAgent() {
        try {
            int cursor = readCursor();
            int totalMessages = mainAgent.getMessages().size();
            extractionTargetCount.set(totalMessages);
            var agent = AgentFork.fork(mainAgent, new AgentFork.ForkConfig("extraction", EXTRACTION_MAX_TURNS, (double) EXTRACTION_TEMPERATURE, false, null));
            agent.injectUserMessage(buildExtractionPrompt(cursor, totalMessages, EXTRACTION_MAX_TURNS));
            agent.continueWithInjectedMessage();
        } catch (Exception e) {
            LOGGER.warn("{} agent failed: {}", "extraction", e.getMessage());
        } finally {
            extractionTargetCount.set(-1);
        }
    }

    private String buildExtractionPrompt(int cursor, int totalMessages, int maxTurns) {
        String cursorInfo = cursor >= 0
                ? "Messages 0–" + (cursor - 1) + " have been extracted (cursor=" + cursor + ", total=" + totalMessages + ")."
                : "No messages have been extracted yet (total=" + totalMessages + ").";
        return ExtractionPrompt.format(workspace, cursorInfo, ZonedDateTime.now(timezone), maxTurns);
    }

    private List<Path> findLockFiles() {
        if (!Files.isDirectory(dailyLogsDir)) return List.of();
        try (Stream<Path> stream = Files.list(dailyLogsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(LOCK_SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOGGER.warn("Failed to list lock files: {}", e.getMessage());
            return List.of();
        }
    }
}
