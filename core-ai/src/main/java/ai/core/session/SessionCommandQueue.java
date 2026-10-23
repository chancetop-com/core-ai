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
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Session-level priority command queue.
 * USER_INPUT has higher priority than TASK_NOTIFICATION.
 * drainSameMode() ensures messages of different modes are never mixed in one batch.
 */
public class SessionCommandQueue {

    private static final Pattern TASK_ID_PATTERN = Pattern.compile("<task-id>\\s*([^<]+?)\\s*</task-id>");

    private final Queue<QueuedCommand> queue = new PriorityQueue<>(
            Comparator.comparingInt((QueuedCommand command) -> command.mode().priority)
                    .thenComparingLong(QueuedCommand::sequence)
    );
    private final AtomicLong sequence = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private volatile Predicate<String> consumedTaskFilter;

    /**
     * A terminal task whose result the session already consumed must not start a notification turn.
     * The filter is keyed by task id and is consulted when the queue is drained, so a task consumed
     * while its notification is still waiting is dropped as well.
     */
    void setConsumedTaskFilter(Predicate<String> filter) {
        this.consumedTaskFilter = filter;
    }

    public void enqueueUserInput(String value, Map<String, Object> variables) {
        enqueueUserInput(value, variables, null);
    }

    /**
     * @return false when the queue is already at its limit — the caller (the server) turns that into a
     *         visible error and idle status instead of silently growing an unbounded queue.
     */
    public boolean enqueueUserInput(String value, Map<String, Object> variables, List<AttachedContent> attachedContents) {
        return enqueue(CommandMode.USER_INPUT, new QueuedMessage(value, variables, attachedContents));
    }

    public boolean enqueueTaskNotification(String value) {
        return enqueue(CommandMode.TASK_NOTIFICATION, new QueuedMessage(value, null, null));
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
        lock.lock();
        try {
            if (queue.isEmpty()) return new CommandBatch(null, List.of());
            var targetMode = queue.peek().mode();
            var values = new ArrayList<QueuedMessage>();
            while (!queue.isEmpty() && queue.peek().mode() == targetMode) {
                var message = queue.poll().value();
                if (targetMode == CommandMode.TASK_NOTIFICATION && isConsumedNotification(message.value())) {
                    continue;
                }
                values.add(message);
            }
            return new CommandBatch(targetMode, values);
        } finally {
            lock.unlock();
        }
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
        lock.lock();
        try {
            queue.removeIf(cmd -> cmd.mode() == CommandMode.TASK_NOTIFICATION);
        } finally {
            lock.unlock();
        }
    }

    private boolean isConsumedNotification(String notification) {
        var filter = consumedTaskFilter;
        if (filter == null) return false;
        var matcher = TASK_ID_PATTERN.matcher(notification);
        return matcher.find() && filter.test(matcher.group(1));
    }

    public enum CommandMode {
        USER_INPUT(0),
        TASK_NOTIFICATION(1);

        final int priority;

        CommandMode(int priority) {
            this.priority = priority;
        }
    }

    public record QueuedMessage(String value, Map<String, Object> variables, List<AttachedContent> attachedContents) {

    }

    public record QueuedCommand(CommandMode mode, QueuedMessage value, long sequence) {

    }

    public record CommandBatch(CommandMode mode, List<QueuedMessage> values) {
        public boolean isEmpty() {
            return values.isEmpty();
        }
    }
}
