package ai.core.tool.wait;

import ai.core.tool.ToolCallResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Completion bookkeeping for one task source. Every task generation is registered before its id is
 * exposed, keeps the authoritative terminal snapshot after it finishes, and hands each waiter an
 * independent future so cancelling one wait never cancels the task or another waiter.
 *
 * <p>In-process only: entries live as long as the source (session or process). A durable
 * continuation needs the store-level protocol described in the design, not this map.
 *
 * @author stephen
 */
public final class TaskCompletionRegistry {
    private static final int MAX_RETAINED_ENTRIES = 200;

    private static String notificationKey(String taskId, long generation) {
        return taskId + "#" + generation;
    }

    private final String backend;
    private final String backendOwner;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Set<String> suppressedNotifications = ConcurrentHashMap.newKeySet();
    private final AtomicLong generations = new AtomicLong();

    public TaskCompletionRegistry(String backend, String backendOwner) {
        this.backend = backend;
        this.backendOwner = backendOwner;
    }

    /**
     * Registers a new generation for the id before the worker can publish anything. A previous
     * unfinished generation is settled as replaced so a waiter of the old run can never hang forever.
     */
    public Entry register(String taskId, String ownerScope) {
        var entry = new Entry(this, new TaskRef(ownerScope, backend, backendOwner, taskId, generations.incrementAndGet()));
        var previous = entries.put(taskId, entry);
        if (previous != null) {
            previous.complete(ToolCallResult.failed("Task replaced by a newer run: " + taskId));
        }
        trim();
        return entry;
    }

    public Optional<Entry> find(String taskId, String ownerScope) {
        var entry = entries.get(taskId);
        if (entry == null || !entry.visibleTo(ownerScope)) return Optional.empty();
        return Optional.of(entry);
    }

    public Optional<Entry> find(String taskId) {
        return Optional.ofNullable(entries.get(taskId));
    }

    /** True when the generation's automatic notification was suppressed by an explicit wait. */
    public boolean isNotificationSuppressed(String taskId, Long generation) {
        if (generation != null) return suppressedNotifications.contains(notificationKey(taskId, generation));
        var prefix = taskId + "#";
        return suppressedNotifications.stream().anyMatch(key -> key.startsWith(prefix));
    }

    private void suppress(String taskId, long generation) {
        suppressedNotifications.add(notificationKey(taskId, generation));
    }

    private void release(String taskId, long generation) {
        suppressedNotifications.remove(notificationKey(taskId, generation));
    }

    /** Terminal entries stay readable for late polls; only the oldest finished ones are dropped. */
    private void trim() {
        if (entries.size() <= MAX_RETAINED_ENTRIES) return;
        var finished = new ArrayList<>(entries.values());
        finished.sort(Comparator.comparingLong(Entry::completedAtMs));
        var excess = entries.size() - MAX_RETAINED_ENTRIES;
        for (var entry : finished) {
            if (excess <= 0) break;
            if (!entry.isDone()) continue;
            entries.remove(entry.ref().taskId(), entry);
            excess--;
        }
    }

    public static final class Entry {
        private final TaskCompletionRegistry registry;
        private final TaskRef ref;
        private final List<Waiter> waiters = new ArrayList<>();
        private volatile String ownerScope;
        private volatile ToolCallResult terminal;
        private volatile long completedAtMs;

        private Entry(TaskCompletionRegistry registry, TaskRef ref) {
            this.registry = registry;
            this.ref = ref;
            this.ownerScope = ref.ownerScope();
        }

        public TaskRef ref() {
            return ref;
        }

        public boolean visibleTo(String scope) {
            var owner = ownerScope;
            return owner == null || owner.equals(scope);
        }

        /** The executor binds the issuing session after the tool call has returned the task id. */
        public void bindOwner(String ownerScope) {
            this.ownerScope = ownerScope;
        }

        public boolean isDone() {
            return terminal != null;
        }

        public boolean hasWaiters() {
            synchronized (this) {
                return !waiters.isEmpty();
            }
        }

        public ToolCallResult peek() {
            var snapshot = terminal;
            return snapshot == null ? null : snapshot.snapshotCopy();
        }

        public long completedAtMs() {
            return completedAtMs;
        }

        /** Linearized with {@link #complete}: a terminal state observed here can never be missed. */
        public Waiter subscribe() {
            var waiter = new Waiter(this);
            synchronized (this) {
                if (terminal != null) {
                    waiter.settle(terminal.snapshotCopy());
                } else {
                    waiters.add(waiter);
                }
            }
            return waiter;
        }

        public void complete(ToolCallResult result) {
            List<Waiter> toNotify;
            synchronized (this) {
                if (terminal != null) return;
                terminal = result.snapshotCopy();
                completedAtMs = System.currentTimeMillis();
                toNotify = List.copyOf(waiters);
                waiters.clear();
            }
            for (var waiter : toNotify) {
                waiter.settle(terminal.snapshotCopy());
            }
        }

        /** A live wait stops this generation from starting an automatic notification turn. */
        public void suppressAutoNotification() {
            registry.suppress(ref.taskId(), ref.generation());
        }

        /** The wait ended before the task did; the normal notification policy resumes. */
        public void releaseAutoNotification() {
            registry.release(ref.taskId(), ref.generation());
        }

        public boolean isNotificationSuppressed() {
            return registry.isNotificationSuppressed(ref.taskId(), ref.generation());
        }
    }

    public static final class Waiter implements AutoCloseable {
        private final Entry entry;
        private final CompletableFuture<ToolCallResult> future = new CompletableFuture<>();
        private final AtomicBoolean settled = new AtomicBoolean();

        private Waiter(Entry entry) {
            this.entry = entry;
        }

        public TaskRef ref() {
            return entry.ref();
        }

        public CompletableFuture<ToolCallResult> future() {
            return future;
        }

        /** Settles only this waiter; the shared completion and the underlying task are untouched. */
        public void cancel(String reason) {
            settle(ToolCallResult.failed(reason));
        }

        private void settle(ToolCallResult result) {
            if (settled.compareAndSet(false, true)) {
                future.complete(result);
            }
        }

        @Override
        public void close() {
            synchronized (entry) {
                entry.waiters.remove(this);
            }
        }
    }
}
