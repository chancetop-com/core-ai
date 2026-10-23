package ai.core.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Messages accepted while a turn is running. They are drained by the agent loop before the next
 * model call, so they take effect within the same turn without touching the in-flight request.
 *
 * @author stephen
 */
public final class PendingUserInputQueue {
    private final Deque<PendingUserInput> inputs = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final int maxSize;
    private boolean closed;

    public PendingUserInputQueue(int maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * @return false when the turn already ended or the queue is full; the caller then falls back
     * to the session queue instead of losing the message.
     */
    public boolean offer(String message, Map<String, Object> variables, List<AttachedContent> attachedContents) {
        lock.lock();
        try {
            if (closed || maxSize > 0 && inputs.size() >= maxSize) return false;
            inputs.addLast(new PendingUserInput(message, variables, attachedContents));
            return true;
        } finally {
            lock.unlock();
        }
    }

    public List<PendingUserInput> drain() {
        lock.lock();
        try {
            return drainLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ends the turn's input window atomically: every offer either lands before this call and is
     * returned, or is rejected afterwards and handled by the session queue.
     */
    public List<PendingUserInput> closeAndDrain() {
        lock.lock();
        try {
            closed = true;
            return drainLocked();
        } finally {
            lock.unlock();
        }
    }

    private List<PendingUserInput> drainLocked() {
        if (inputs.isEmpty()) return List.of();
        var drained = new ArrayList<>(inputs);
        inputs.clear();
        return drained;
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return inputs.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return inputs.size();
        } finally {
            lock.unlock();
        }
    }

    public record PendingUserInput(String message, Map<String, Object> variables, List<AttachedContent> attachedContents) {
        public PendingUserInput {
            variables = variables == null ? null : new HashMap<>(variables);
            attachedContents = attachedContents == null ? null : new ArrayList<>(attachedContents);
        }
    }
}
