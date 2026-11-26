package ai.core.session;

import ai.core.agent.AttachedContent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session-level priority command queue.
 * USER_INPUT has higher priority than TASK_NOTIFICATION.
 * drainSameMode() ensures messages of different modes are never mixed in one batch.
 */
public class SessionCommandQueue {

    private static final Pattern TASK_ID_PATTERN = Pattern.compile("<task-id>\\s*([^<]+?)\\s*</task-id>");
    private static final Pattern TASK_GENERATION_PATTERN = Pattern.compile("<task-generation>\\s*(\\d+)\\s*</task-generation>");

    private final Queue<QueuedCommand> queue = new PriorityQueue<>(
            Comparator.comparingInt((QueuedCommand command) -> command.mode().priority)
                    .thenComparingLong(QueuedCommand::sequence)
    );
    private final AtomicLong sequence = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private volatile BiPredicate<String, Long> taskNotificationFilter;
    private volatile BiConsumer<String, Long> notificationDroppedListener;

    /**
     * A terminal task whose result the session already consumed (or whose automatic notification a
     * live wait suppressed) must not start a notification turn. The filter is keyed by task id and
     * generation and is consulted when the queue is drained, so a task consumed while its notification
     * is still waiting is dropped as well. A notification without a generation is matched by task id.
     */
    void setTaskNotificationFilter(BiPredicate<String, Long> filter) {
        this.taskNotificationFilter = filter;
    }

    /** Compatibility for hosts that only know task ids; a generation-less notification is matched by id. */
    void setConsumedTaskFilter(Predicate<String> filter) {
        this.taskNotificationFilter = (taskId, generation) -> filter.test(taskId);
    }

    void setNotificationDroppedListener(BiConsumer<String, Long> listener) {
        this.notificationDroppedListener = listener;
    }

    public void enqueueUserInput(String value, Map<String, Object> variables) {
        enqueueUserInput(value, variables, null);
    }

    /**
     * @return false when the queue is already at its limit — the caller (the server) turns that into a
     *         visible error and idle status instead of silently growing an unbounded queue.
     */
    public boolean enqueueUserInput(String value, Map<String, Object> variables, List<AttachedContent> attachedContents) {
        return enqueueUserInput(value, variables, attachedContents, null);
    }

    public boolean enqueueUserInput(String value, Map<String, Object> variables, List<AttachedContent> attachedContents, String turnId) {
        return enqueue(CommandMode.USER_INPUT, new QueuedMessage(value, variables, attachedContents, turnId));
    }

    public boolean enqueueTaskNotification(String value) {
        return enqueue(CommandMode.TASK_NOTIFICATION, new QueuedMessage(value, null, null, null));
    }

    /**
     * Returns a batch already accepted from the queue without applying the capacity limit again.
     * Used when a queued Task Run must wait for the previous run to finish.
     */
    public void requeueUserInputs(List<QueuedMessage> messages) {
        lock.lock();
        try {
            for (var message : messages) {
                queue.add(new QueuedCommand(CommandMode.USER_INPUT, message, sequence.getAndIncrement()));
            }
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private boolean enqueue(CommandMode mode, QueuedMessage message) {
        lock.lock();
        try {
            var max = SessionRuntimeLimits.current().maxQueuedMessages;
            if (max > 0 && queue.size() >= max) return false;
            queue.add(new QueuedCommand(mode, message, sequence.getAndIncrement()));
            notEmpty.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Commands waiting to be executed (queue cap signal for observation). */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drains all commands of the highest-priority mode present in the queue.
     * Returns a CommandBatch with the shared mode and all values.
     * Consumed task notifications are dropped instead of starting an empty turn.
     */
    public CommandBatch drainSameMode() {
        var dropped = new ArrayList<String>();
        CommandBatch batch;
        lock.lock();
        try {
            if (queue.isEmpty()) return new CommandBatch(null, List.of());
            var targetMode = queue.peek().mode();
            var values = new ArrayList<QueuedMessage>();
            while (!queue.isEmpty() && queue.peek().mode() == targetMode) {
                var message = queue.poll().value();
                if (targetMode == CommandMode.TASK_NOTIFICATION && isConsumedNotification(message.value())) {
                    dropped.add(message.value());
                    continue;
                }
                values.add(message);
            }
            batch = new CommandBatch(targetMode, values);
        } finally {
            lock.unlock();
        }
        dropped.forEach(this::notifyNotificationDropped);
        return batch;
    }

    /**
     * Blocks until the queue is non-empty. Zero CPU consumption.
     */
    public void awaitNonEmpty() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty()) {
                notEmpty.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until the queue is non-empty or the timeout expires.
     * Returns true if the queue is non-empty, false if timeout expired.
     */
    public boolean awaitNonEmpty(long timeout, TimeUnit unit) throws InterruptedException {
        var deadline = System.nanoTime() + unit.toNanos(timeout);
        lock.lock();
        try {
            while (queue.isEmpty()) {
                var remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                notEmpty.await(remaining, TimeUnit.NANOSECONDS);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes all TASK_NOTIFICATION commands from the queue.
     * Called after a turn is cancelled to prevent stale task notifications
     * from spawning spurious turns.
     */
    public void drainTaskNotifications() {
        var dropped = new ArrayList<String>();
        lock.lock();
        try {
            queue.removeIf(cmd -> {
                if (cmd.mode() != CommandMode.TASK_NOTIFICATION) return false;
                dropped.add(cmd.value().value());
                return true;
            });
        } finally {
            lock.unlock();
        }
        dropped.forEach(this::notifyNotificationDropped);
    }

    private void notifyNotificationDropped(String notification) {
        var listener = notificationDroppedListener;
        if (listener == null) return;
        Matcher taskId = TASK_ID_PATTERN.matcher(notification);
        if (!taskId.find()) return;
        Matcher generation = TASK_GENERATION_PATTERN.matcher(notification);
        var parsedGeneration = generation.find() ? Long.valueOf(generation.group(1)) : null;
        listener.accept(taskId.group(1), parsedGeneration);
    }

    private boolean isConsumedNotification(String notification) {
        var filter = taskNotificationFilter;
        if (filter == null) return false;
        Matcher taskId = TASK_ID_PATTERN.matcher(notification);
        if (!taskId.find()) return false;
        Matcher generation = TASK_GENERATION_PATTERN.matcher(notification);
        var parsedGeneration = generation.find() ? Long.valueOf(generation.group(1)) : null;
        return filter.test(taskId.group(1), parsedGeneration);
    }

    /**
     * Removes accepted user inputs that never started, by turn id. A cancelled Task Run drops the
     * inputs it had accepted but not yet handed to an execution, so a stop does not fire them later.
     */
    public int dropUserInputs(java.util.Collection<String> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) return 0;
        int dropped = 0;
        lock.lock();
        try {
            var iterator = queue.iterator();
            while (iterator.hasNext()) {
                var command = iterator.next();
                if (command.mode() != CommandMode.USER_INPUT) continue;
                var turnId = command.value().turnId();
                if (turnId != null && turnIds.contains(turnId)) {
                    iterator.remove();
                    dropped++;
                }
            }
        } finally {
            lock.unlock();
        }
        return dropped;
    }

    public enum CommandMode {
        TASK_NOTIFICATION(0),
        USER_INPUT(1);

        final int priority;

        CommandMode(int priority) {
            this.priority = priority;
        }
    }

    public record QueuedMessage(String value, Map<String, Object> variables, List<AttachedContent> attachedContents, String turnId) {

    }

    public record QueuedCommand(CommandMode mode, QueuedMessage value, long sequence) {

    }

    public record CommandBatch(CommandMode mode, List<QueuedMessage> values) {
        public boolean isEmpty() {
            return values.isEmpty();
        }
    }
}
