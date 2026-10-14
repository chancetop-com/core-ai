package ai.core.agent.subagent;

import ai.core.agent.Agent;
import ai.core.agent.AgentPersistence;
import ai.core.agent.NodeStatus;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.persistence.PersistenceProvider;
import ai.core.utils.MessageTokenCounterUtil;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The session's subagent threads: which subagents exist, which one is running, and their persisted
 * context. One entry per {@code task_id}; the index is loaded lazily on first access, so a restored
 * session can list its threads without rebuilding any agent until one is actually continued.
 * Persistence failures never fail the run — the thread simply stays memory-only.
 *
 * @author stephen
 */
public final class SubagentThreadRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubagentThreadRegistry.class);
    public static final int DEFAULT_MAX_LIVE = 8;

    private final String sessionId;
    private final PersistenceProvider persistenceProvider;
    private final int maxLive;
    private final Map<String, SubagentThread> threads = new ConcurrentHashMap<>();
    private final Object indexLock = new Object();
    private volatile boolean indexLoaded;

    public SubagentThreadRegistry(String sessionId, PersistenceProvider persistenceProvider) {
        this(sessionId, persistenceProvider, Integer.getInteger("subagent.thread.maxLive", DEFAULT_MAX_LIVE));
    }

    public SubagentThreadRegistry(String sessionId, PersistenceProvider persistenceProvider, int maxLive) {
        this.sessionId = sessionId;
        // a session that cannot be named cannot own durable threads; the registry degrades to memory-only
        this.persistenceProvider = sessionId == null || sessionId.isBlank() ? null : persistenceProvider;
        this.maxLive = maxLive;
    }

    public Optional<SubagentThread> find(String taskId) {
        ensureIndexLoaded();
        return Optional.ofNullable(threads.get(taskId));
    }

    /** All threads, most recently active first. */
    public List<SubagentThread> list() {
        ensureIndexLoaded();
        return threads.values().stream()
                .sorted(Comparator.comparingLong(SubagentThread::lastActiveAtMs).reversed())
                .toList();
    }

    public SubagentThread register(String taskId, SubagentThreadKind kind, String type, String description,
                                   String model, Agent agent) {
        ensureIndexLoaded();
        var thread = new SubagentThread(taskId, kind, type, description, model);
        if (agent != null) thread.attachAgent(agent);
        threads.put(taskId, thread);
        return thread;
    }

    /** One run at a time per thread: a second continuation gets false while the thread is running. */
    public boolean tryAcquire(SubagentThread thread) {
        return thread.tryStart();
    }

    /** Ends a run, recording what the thread looks like now. Safe to call from the runner's thread. */
    public void release(SubagentThread thread, Agent agent, String lastSummary) {
        var contextTokens = agent == null ? 0 : MessageTokenCounterUtil.count(agent.getMessages());
        thread.finishRun(lastSummary, contextTokens);
        thread.stopRunning();
    }

    /** Releases a thread that never ran (rehydrate or submit failed) without counting a run. */
    public void abort(SubagentThread thread) {
        thread.stopRunning();
    }

    /**
     * Rebuilds a dormant thread: a fresh agent from the factory, then the persisted messages minus
     * their system message, so the run picks up the current system prompt and environment.
     */
    public Agent rehydrate(SubagentThread thread, Supplier<Agent> factory) {
        Agent agent;
        try {
            agent = factory.get();
        } catch (RuntimeException e) {
            throw new SubagentThreadUnavailableException(
                    "subagent type '" + thread.type() + "' is no longer available: " + e.getMessage(), e);
        }
        if (persistenceProvider != null) {
            var data = persistenceProvider.load(SubagentPersistenceKeys.thread(sessionId, thread.taskId()))
                    .orElseThrow(() -> new SubagentThreadUnavailableException("its saved context could not be found"));
            restoreMessages(agent, data);
        }
        thread.attachAgent(agent);
        return agent;
    }

    /** Thread data plus the index; called after every run. */
    public void persist(SubagentThread thread) {
        if (persistenceProvider == null) return;
        try {
            synchronized (indexLock) {
                writeThreadData(thread);
                writeIndex();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("failed to persist subagent thread, sessionId={}, taskId={}", sessionId, thread.taskId(), e);
        }
    }

    /** Session close: writes every live thread and the index once. */
    public void persistAll() {
        if (persistenceProvider == null || threads.isEmpty()) return;
        try {
            synchronized (indexLock) {
                for (var thread : threads.values()) {
                    writeThreadData(thread);
                }
                writeIndex();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("failed to persist subagent threads, sessionId={}", sessionId, e);
        }
    }

    /** Drops the agents of the least recently active threads once more than {@code maxLive} are in memory. */
    public void evictIfNeeded() {
        // without a store an eviction would lose the agent's context for good
        if (persistenceProvider == null) return;
        synchronized (indexLock) {
            var live = threads.values().stream()
                    .filter(thread -> thread.agent() != null && !thread.isRunning())
                    .sorted(Comparator.comparingLong(SubagentThread::lastActiveAtMs))
                    .toList();
            var excess = live.size() - maxLive;
            for (int i = 0; i < excess; i++) {
                var thread = live.get(i);
                writeThreadData(thread);
                thread.detachAgent();
            }
        }
    }

    private void restoreMessages(Agent agent, String data) {
        try {
            var domain = JSON.fromJSON(AgentPersistence.AgentPersistenceDomain.class, data);
            var messages = domain.messages == null ? new ArrayList<Message>() : new ArrayList<>(domain.messages);
            // drop the persisted system message: the fresh one carries the current environment and instructions
            if (!messages.isEmpty() && messages.getFirst().role == RoleType.SYSTEM) {
                messages.removeFirst();
            }
            agent.restorePersistedState(messages, null);
            agent.updateNodeStatus(NodeStatus.INITED);
        } catch (RuntimeException e) {
            throw new SubagentThreadUnavailableException("its saved context could not be read", e);
        }
    }

    private void ensureIndexLoaded() {
        if (indexLoaded) return;
        synchronized (indexLock) {
            if (indexLoaded) return;
            indexLoaded = true;
            if (persistenceProvider == null) return;
            try {
                persistenceProvider.load(SubagentPersistenceKeys.index(sessionId)).ifPresent(this::loadIndex);
            } catch (RuntimeException e) {
                LOGGER.warn("failed to load subagent thread index, sessionId={}", sessionId, e);
            }
        }
    }

    private void loadIndex(String data) {
        var index = JSON.fromJSON(SubagentThreadIndex.class, data);
        if (index.threads == null) return;
        for (var ref : index.threads) {
            if (ref.taskId == null) continue;
            var kind = ref.kind != null ? ref.kind : SubagentThreadKind.TASK;
            var thread = new SubagentThread(ref.taskId, kind, ref.type, ref.description, ref.model);
            thread.restoreCounters(
                    ref.runCount != null ? ref.runCount : 0,
                    ref.createdAtMs != null ? ref.createdAtMs : 0,
                    ref.lastRunAtMs != null ? ref.lastRunAtMs : 0,
                    ref.contextTokens != null ? ref.contextTokens : 0,
                    ref.lastSummary);
            threads.putIfAbsent(ref.taskId, thread);
        }
    }

    private void writeThreadData(SubagentThread thread) {
        var agent = thread.agent();
        if (agent == null || persistenceProvider == null) return;
        persistenceProvider.save(SubagentPersistenceKeys.thread(sessionId, thread.taskId()),
                JSON.toJSON(AgentPersistence.AgentPersistenceDomain.of(agent.getMessages(), null, agent.getNodeStatus())));
    }

    private void writeIndex() {
        persistenceProvider.save(SubagentPersistenceKeys.index(sessionId), JSON.toJSON(SubagentThreadIndex.of(list())));
    }
}
