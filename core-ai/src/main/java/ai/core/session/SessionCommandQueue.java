package ai.core.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Session-level priority command queue.
 * USER_INPUT has higher priority than TASK_NOTIFICATION.
 * drainSameMode() ensures messages of different modes are never mixed in one batch.
 */
public class SessionCommandQueue {

    private final Queue<QueuedCommand> queue = new PriorityQueue<>(
            Comparator.comparingInt(a -> a.mode().priority)
    );
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    public void enqueueUserInput(String value) {
        enqueue(new QueuedCommand(CommandMode.USER_INPUT, value));
    }

    public void enqueueTaskNotification(String value) {
        enqueue(new QueuedCommand(CommandMode.TASK_NOTIFICATION, value));
    }

    private void enqueue(QueuedCommand command) {
        lock.lock();
        try {
            queue.add(command);
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drains all commands of the highest-priority mode present in the queue.
     * Returns a CommandBatch with the shared mode and all values.
     */
    public CommandBatch drainSameMode() {
        lock.lock();
        try {
            if (queue.isEmpty()) return new CommandBatch(null, List.of());
            var targetMode = queue.peek().mode();
            var values = new ArrayList<String>();
            while (!queue.isEmpty() && queue.peek().mode() == targetMode) {
                values.add(queue.poll().value());
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

    public boolean isEmpty() {
        lock.lock();
        try {
            return queue.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public enum CommandMode {
        USER_INPUT(0),
        TASK_NOTIFICATION(1);

        final int priority;

        CommandMode(int priority) {
            this.priority = priority;
        }
    }

    public record QueuedCommand(CommandMode mode, String value) { }

    public record CommandBatch(CommandMode mode, List<String> values) {
        public boolean isEmpty() {
            return values.isEmpty();
        }
    }
}
