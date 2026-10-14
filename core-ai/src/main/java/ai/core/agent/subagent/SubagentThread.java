package ai.core.agent.subagent;

import ai.core.agent.Agent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One subagent conversation kept alive inside a session: the agent instance plus the metadata needed
 * to list it, continue it and persist it between runs. The run gate is a CAS flag rather than a lock
 * because a background run releases it from the executor thread that never acquired it.
 *
 * @author stephen
 */
public final class SubagentThread {
    private static final int MAX_SUMMARY_LENGTH = 200;

    private static String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private final String taskId;
    private final SubagentThreadKind kind;
    private final String type;
    private final String model;
    private volatile long createdAtMs;
    private volatile String description;
    private volatile Agent agent;
    private final AtomicInteger runCount = new AtomicInteger();
    private volatile long lastRunAtMs;
    private volatile long contextTokens;
    private volatile String lastSummary;
    private final AtomicBoolean running = new AtomicBoolean(false);

    SubagentThread(String taskId, SubagentThreadKind kind, String type, String description, String model) {
        this.taskId = taskId;
        this.kind = kind;
        this.type = type;
        this.description = description;
        this.model = model;
        this.createdAtMs = System.currentTimeMillis();
    }

    public String taskId() {
        return taskId;
    }

    public SubagentThreadKind kind() {
        return kind;
    }

    public String type() {
        return type;
    }

    public String description() {
        return description;
    }

    public String model() {
        return model;
    }

    public Agent agent() {
        return agent;
    }

    public int runCount() {
        return runCount.get();
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public long lastRunAtMs() {
        return lastRunAtMs;
    }

    public long contextTokens() {
        return contextTokens;
    }

    public String lastSummary() {
        return lastSummary;
    }

    public SubagentThreadState state() {
        if (running.get()) return SubagentThreadState.RUNNING;
        return agent == null ? SubagentThreadState.DORMANT : SubagentThreadState.LIVE;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Last activity for LRU ordering: a thread that never ran counts from its creation. */
    public long lastActiveAtMs() {
        return lastRunAtMs > 0 ? lastRunAtMs : createdAtMs;
    }

    public void updateDescription(String description) {
        this.description = description;
    }

    boolean tryStart() {
        return running.compareAndSet(false, true);
    }

    void finishRun(String summary, long contextTokens) {
        runCount.incrementAndGet();
        this.lastRunAtMs = System.currentTimeMillis();
        this.contextTokens = contextTokens;
        this.lastSummary = truncate(summary, MAX_SUMMARY_LENGTH);
    }

    void stopRunning() {
        running.set(false);
    }

    void attachAgent(Agent agent) {
        this.agent = agent;
    }

    void detachAgent() {
        this.agent = null;
    }

    /** Restores the counters of a thread rebuilt from the persisted index. */
    void restoreCounters(int runCount, long createdAtMs, long lastRunAtMs, long contextTokens, String lastSummary) {
        this.runCount.set(runCount);
        if (createdAtMs > 0) this.createdAtMs = createdAtMs;
        this.lastRunAtMs = lastRunAtMs;
        this.contextTokens = contextTokens;
        this.lastSummary = lastSummary;
    }
}
