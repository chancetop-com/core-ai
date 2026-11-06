package ai.core.cli.memory;

import ai.core.agent.Agent;
import ai.core.cli.utils.AgentFork;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Deterministic memory governance: keeps the knowledge index in sync with the pages on disk.
 *
 * <p>This is a standalone pass, not a side effect of a task turn. It runs at startup, once per day
 * when the agent is idle, and on demand from {@code /memory maintain} or the desktop memory panel.
 */
public final class MemoryMaintenanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryMaintenanceService.class);
    private static final int CURATOR_MAX_TURNS = 20;
    private static final double CURATOR_TEMPERATURE = 0.2;

    private final Path workspace;
    private final MdMemoryProvider memoryProvider;
    private final Supplier<Agent> mainAgent;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile LocalDate lastRunDate;

    MemoryMaintenanceService(Path workspace, MdMemoryProvider memoryProvider, Supplier<Agent> mainAgent) {
        this.workspace = workspace;
        this.memoryProvider = memoryProvider;
        this.mainAgent = mainAgent;
    }

    /**
     * Rebuilds the index and prunes the changelog. Returns {@code null} when another pass is running
     * or when the pass failed; callers treat both as "try again later".
     */
    public MemoryIndexMaintainer.Result run() {
        if (!running.compareAndSet(false, true)) {
            return null;
        }
        try {
            var result = MemoryIndexMaintainer.reconcile(workspace);
            KnowledgeLogTool.pruneOldEntries(workspace);
            var agent = mainAgent.get();
            if (agent != null && result.changed()) {
                MemorySectionManager.reloadAgentMemorySection(agent, memoryProvider);
            }
            lastRunDate = LocalDate.now(MemoryTriggerService.getTimezone());
            LOGGER.info("memory maintenance completed: added={}, removed={}, updated={}, total={}",
                    result.added(), result.removed(), result.updated(), result.total());
            return result;
        } catch (Exception e) {
            LOGGER.warn("memory maintenance failed: {}", e.getMessage());
            return null;
        } finally {
            running.set(false);
        }
    }

    public boolean runDailyIfDue() {
        if (LocalDate.now(MemoryTriggerService.getTimezone()).equals(lastRunDate)) {
            return false;
        }
        return run() != null;
    }

    /**
     * Runs the semantic curator as an isolated agent (no task history, its own tool set), then
     * rebuilds the index deterministically. Returns {@code null} when another pass is running or
     * when no main agent is available yet.
     */
    public CuratorResult runCurator() {
        var source = mainAgent.get();
        if (source == null || !running.compareAndSet(false, true)) {
            return null;
        }
        boolean completed = false;
        try {
            var tools = new ArrayList<ToolCall>(BuiltinTools.FILE_OPERATIONS);
            tools.add(KnowledgeLogTool.addBuilder().workspace(workspace).build());
            var curator = AgentFork.forkConfigOnly(source,
                    new AgentFork.ForkConfig("memory-curator", CURATOR_MAX_TURNS, CURATOR_TEMPERATURE,
                            false, null, tools));
            curator.injectUserMessage(MemoryCuratorPrompt.format(workspace));
            curator.continueWithInjectedMessage();
            completed = true;
            LOGGER.info("memory curator completed");
        } catch (Exception e) {
            LOGGER.warn("memory curator failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
        return new CuratorResult(completed, run());
    }

    public boolean isRunning() {
        return running.get();
    }

    public record CuratorResult(boolean completed, MemoryIndexMaintainer.Result index) {
    }
}
