package ai.core.server.web.sse;

import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.sse.SseBatchToolStartEvent;
import ai.core.api.server.session.sse.SseCustomEvent;
import ai.core.api.server.session.sse.SseEnvironmentOutputChunkEvent;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SsePlanUpdateEvent;
import ai.core.api.server.session.sse.SseReasoningChunkEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.api.server.session.sse.SseTaskStatusEvent;
import ai.core.api.server.session.sse.SseTextChunkEvent;
import ai.core.api.server.session.sse.SseToolApprovalRequestEvent;
import ai.core.api.server.session.sse.SseToolStartEvent;
import ai.core.api.server.session.sse.SseTurnCompleteEvent;
import ai.core.server.messaging.SseEventHelper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Owner-pod event source for one session. It assigns a monotonic sequence, keeps a bounded replay
 * snapshot and fans events out to local browsers and cross-pod relay subscribers.
 *
 * @author stephen
 */
@SuppressWarnings("PMD.AvoidSynchronizedAtMethodLevel")
public final class SessionEventHub {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionEventHub.class);
    static final int MAX_BUFFER_SIZE = 50_000;
    static final long DEFAULT_MAX_BUFFER_BYTES = 8L * 1024 * 1024;
    static final int MAX_REPLAY_EVENTS = 1_000;
    private static final int MAX_SUBSCRIBER_QUEUE = 2_048;
    private static final int EVENT_OVERHEAD_BYTES = 256;
    private static final int STRING_OVERHEAD_BYTES = 40;

    private static long estimatedBytes(SseBaseEvent event) {
        long size = EVENT_OVERHEAD_BYTES;
        if (event instanceof SseTextChunkEvent chunk) return size + stringBytes(chunk.content);
        if (event instanceof SseReasoningChunkEvent chunk) return size + stringBytes(chunk.content);
        if (event instanceof ai.core.api.server.session.sse.SseToolResultEvent result) return size + stringBytes(result.result);
        if (event instanceof SseCustomEvent custom) return size + stringBytes(custom.data) + stringBytes(custom.card);
        if (event instanceof SseToolStartEvent start) return size + stringBytes(start.toolNotes) + mapBytes(start.toolArgs);
        if (event instanceof SseBatchToolStartEvent batch) {
            size += stringBytes(batch.group);
            if (batch.tools != null) {
                for (var tool : batch.tools) {
                    size += stringBytes(tool.arguments) + stringBytes(tool.toolName);
                }
            }
            return size;
        }
        if (event instanceof SseEnvironmentOutputChunkEvent chunk) return size + stringBytes(chunk.chunk);
        if (event instanceof SseErrorEvent error) return size + stringBytes(error.message) + stringBytes(error.detail);
        if (event instanceof SseToolApprovalRequestEvent approval) return size + stringBytes(approval.arguments);
        if (event instanceof SsePlanUpdateEvent plan) {
            if (plan.todos != null) {
                for (var todo : plan.todos) size += stringBytes(todo.content);
            }
            return size;
        }
        if (event instanceof SseTurnCompleteEvent complete) return size + stringBytes(complete.output);
        return size;
    }

    private static long stringBytes(String value) {
        return value == null ? 0 : STRING_OVERHEAD_BYTES + 2L * value.length();
    }

    private static long mapBytes(Map<String, Object> map) {
        if (map == null) return 0;
        long size = 0;
        for (var entry : map.entrySet()) {
            size += stringBytes(entry.getKey()) + valueBytes(entry.getValue());
        }
        return size;
    }

    private static long valueBytes(Object value) {
        if (value == null) return 0;
        if (value instanceof String text) return stringBytes(text);
        if (value instanceof Map<?, ?> nested) {
            long size = 0;
            for (var entry : nested.entrySet()) {
                size += stringBytes(String.valueOf(entry.getKey())) + valueBytes(entry.getValue());
            }
            return size;
        }
        if (value instanceof Collection<?> values) {
            long size = 0;
            for (var item : values) size += valueBytes(item);
            return size;
        }
        if (value instanceof Number || value instanceof Boolean) return 16;
        return 64;
    }

    private final String sessionId;
    private final Deque<BufferedEvent> buffer = new ArrayDeque<>();
    private final List<Subscription> subscribers = new ArrayList<>();
    private long lastSeq;
    private long bufferedBytes;
    private boolean replayTerminated;
    private long droppedEvents;
    // visible for tests
    long maxBufferBytes = DEFAULT_MAX_BUFFER_BYTES;

    public SessionEventHub(String sessionId) {
        this.sessionId = sessionId;
    }

    public synchronized long publish(SseBaseEvent event) {
        if (event == null) return lastSeq;
        SseEventHelper.initEvent(event, sessionId);

        // Task status belongs to work that outlives a turn; it is delivered live but never replayed.
        if (event instanceof SseTaskStatusEvent) {
            long seq = ++lastSeq;
            deliver(seq, event);
            return seq;
        }

        if (!prepareBuffer(event)) return lastSeq;

        long seq = ++lastSeq;
        appendToBuffer(seq, event);
        deliver(seq, event);
        return seq;
    }

    private boolean prepareBuffer(SseBaseEvent event) {
        var isRunningStatus = event instanceof SseStatusChangeEvent statusChange
                && statusChange.status == SessionStatus.RUNNING;
        var isTerminalEvent = event instanceof SseTurnCompleteEvent
                || event instanceof SseErrorEvent
                || event instanceof SseStatusChangeEvent statusChange
                && statusChange.status != SessionStatus.RUNNING;

        if (isRunningStatus) {
            resetBuffer();
            replayTerminated = false;
            return true;
        }
        if (isTerminalEvent) {
            var isErrorStatus = event instanceof SseStatusChangeEvent statusChange
                    && statusChange.status == SessionStatus.ERROR;
            if (isErrorStatus) {
                buffer.removeIf(entry -> !(entry.event instanceof SseErrorEvent));
                recomputeBufferBytes();
            } else {
                resetBuffer();
            }
            replayTerminated = true;
            return true;
        }
        if (replayTerminated && isTurnActivityEvent(event)) {
            LOGGER.debug("dropping late session event after terminal state, sessionId={}, event={}",
                    sessionId, event.getClass().getSimpleName());
            return false;
        }
        return true;
    }

    private void appendToBuffer(long seq, SseBaseEvent event) {
        if (!buffer.isEmpty() && mergeConsecutiveChunk(buffer.peekLast(), event)) {
            var previous = buffer.removeLast();
            var merged = mergeCopy(previous.event, event);
            var mergedBytes = estimatedBytes(merged);
            buffer.addLast(new BufferedEvent(previous.firstSeq, seq, merged, mergedBytes));
            bufferedBytes += mergedBytes - previous.bytes;
            trimToBudget();
            return;
        }
        long eventBytes = estimatedBytes(event);
        if (eventBytes > maxBufferBytes) {
            // One oversized tool result must not evict the whole turn's replay.
            droppedEvents++;
            return;
        }
        while (!buffer.isEmpty()
                && (buffer.size() >= MAX_BUFFER_SIZE || bufferedBytes + eventBytes > maxBufferBytes)) {
            var removed = buffer.removeFirst();
            bufferedBytes -= removed.bytes;
            droppedEvents++;
        }
        buffer.addLast(new BufferedEvent(seq, seq, event, eventBytes));
        bufferedBytes += eventBytes;
    }

    public synchronized Subscription subscribe(long afterSeq, EventSink sink) {
        var subscription = new Subscription(sink);
        subscribers.add(subscription);
        for (var delivery : replay(afterSeq)) {
            if (!subscription.offer(delivery)) {
                subscription.close();
                subscribers.remove(subscription);
                return subscription;
            }
        }
        return subscription;
    }

    public synchronized SessionStatus status() {
        if (buffer.isEmpty()) return SessionStatus.IDLE;
        Iterator<BufferedEvent> iterator = buffer.descendingIterator();
        while (iterator.hasNext()) {
            var event = iterator.next().event;
            if (event instanceof SseStatusChangeEvent statusChange && statusChange.status != null) {
                return statusChange.status;
            }
            if (event instanceof SseTurnCompleteEvent) return SessionStatus.IDLE;
            if (event instanceof SseErrorEvent) return SessionStatus.ERROR;
        }
        return SessionStatus.RUNNING;
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }

    public synchronized Stats stats() {
        return new Stats(bufferedBytes, buffer.size(), droppedEvents);
    }

    public synchronized void close() {
        resetBuffer();
        var current = new ArrayList<>(subscribers);
        subscribers.clear();
        for (var subscriber : current) subscriber.close();
    }

    private List<Delivery> replay(long afterSeq) {
        var snapshot = new ArrayList<>(buffer);
        if (snapshot.isEmpty() || afterSeq >= lastSeq) return List.of();

        List<BufferedEvent> selected;
        boolean injectRunning = false;
        if (afterSeq <= 0) {
            selected = snapshot;
            injectRunning = isActiveTurn(snapshot) && !isRunning(snapshot.get(0).event);
        } else {
            int exact = indexEndingAt(snapshot, afterSeq);
            if (exact >= 0 && (exact + 1 >= snapshot.size() || snapshot.get(exact + 1).firstSeq == afterSeq + 1)) {
                selected = snapshot.subList(exact + 1, snapshot.size());
            } else {
                selected = fullTurn(snapshot);
                injectRunning = selected.isEmpty() || !isRunning(selected.get(0).event);
            }
        }

        var deliveries = new ArrayList<Delivery>(selected.size() + 1);
        if (injectRunning) deliveries.add(new Delivery(0, syntheticRunning()));
        for (var entry : selected) deliveries.add(new Delivery(entry.lastSeq, entry.event));

        if (deliveries.size() > MAX_REPLAY_EVENTS) {
            int start = deliveries.size() - MAX_REPLAY_EVENTS;
            if (!isRunning(deliveries.get(start).event)) {
                start--;
            }
            if (start < 0) start = 0;
            deliveries = new ArrayList<>(deliveries.subList(start, deliveries.size()));
            LOGGER.warn("session event replay truncated, sessionId={}, replayed={}, max={}",
                    sessionId, deliveries.size(), MAX_REPLAY_EVENTS);
        }
        return deliveries;
    }

    private int indexEndingAt(List<BufferedEvent> snapshot, long seq) {
        for (int i = 0; i < snapshot.size(); i++) {
            if (snapshot.get(i).lastSeq == seq) return i;
        }
        return -1;
    }

    private List<BufferedEvent> fullTurn(List<BufferedEvent> snapshot) {
        int start = 0;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (isRunning(snapshot.get(i).event)) {
                start = i;
                break;
            }
        }
        return snapshot.subList(start, snapshot.size());
    }

    private boolean isActiveTurn(List<BufferedEvent> snapshot) {
        var last = snapshot.get(snapshot.size() - 1).event;
        return !(last instanceof SseTurnCompleteEvent)
                && !(last instanceof SseErrorEvent)
                && !(last instanceof SseStatusChangeEvent statusChange && statusChange.status != SessionStatus.RUNNING);
    }

    private boolean isRunning(SseBaseEvent event) {
        return event instanceof SseStatusChangeEvent statusChange && statusChange.status == SessionStatus.RUNNING;
    }

    private SseStatusChangeEvent syntheticRunning() {
        var event = new SseStatusChangeEvent();
        event.status = SessionStatus.RUNNING;
        SseEventHelper.initEvent(event, sessionId);
        return event;
    }

    private void deliver(long seq, SseBaseEvent event) {
        var iterator = subscribers.iterator();
        while (iterator.hasNext()) {
            var subscriber = iterator.next();
            if (!subscriber.offer(new Delivery(seq, event))) {
                subscriber.close();
                iterator.remove();
            }
        }
    }

    private void resetBuffer() {
        buffer.clear();
        bufferedBytes = 0;
    }

    private void trimToBudget() {
        while (bufferedBytes > maxBufferBytes && !buffer.isEmpty()) {
            var removed = buffer.removeFirst();
            bufferedBytes -= removed.bytes;
            droppedEvents++;
        }
    }

    private void recomputeBufferBytes() {
        long total = 0;
        for (var entry : buffer) total += entry.bytes;
        bufferedBytes = total;
    }

    private boolean mergeConsecutiveChunk(BufferedEvent last, SseBaseEvent incoming) {
        return last.event instanceof SseTextChunkEvent && incoming instanceof SseTextChunkEvent
                || last.event instanceof SseReasoningChunkEvent && incoming instanceof SseReasoningChunkEvent;
    }

    @SuppressFBWarnings("PDP_POORLY_DEFINED_PARAMETER")
    private SseBaseEvent mergeCopy(SseBaseEvent last, SseBaseEvent incoming) {
        if (last instanceof SseTextChunkEvent lastText && incoming instanceof SseTextChunkEvent incomingText) {
            var merged = new SseTextChunkEvent();
            merged.content = (lastText.content == null ? "" : lastText.content)
                    + (incomingText.content == null ? "" : incomingText.content);
            merged.isFinalChunk = incomingText.isFinalChunk;
            merged.sessionId = incomingText.sessionId;
            merged.timestamp = incomingText.timestamp;
            merged.type = incomingText.type;
            return merged;
        }
        var lastReasoning = (SseReasoningChunkEvent) last;
        var incomingReasoning = (SseReasoningChunkEvent) incoming;
        var merged = new SseReasoningChunkEvent();
        merged.content = (lastReasoning.content == null ? "" : lastReasoning.content)
                + (incomingReasoning.content == null ? "" : incomingReasoning.content);
        merged.isFinalChunk = incomingReasoning.isFinalChunk;
        merged.sessionId = incomingReasoning.sessionId;
        merged.timestamp = incomingReasoning.timestamp;
        merged.type = incomingReasoning.type;
        return merged;
    }

    private boolean isTurnActivityEvent(SseBaseEvent event) {
        return !(event instanceof SseStatusChangeEvent)
                && !(event instanceof SseTurnCompleteEvent)
                && !(event instanceof SseErrorEvent);
    }

    public interface EventSink {
        boolean onEvent(long seq, SseBaseEvent event);
    }

    public record Stats(long bufferedBytes, long bufferedEvents, long droppedEvents) {
    }

    private record BufferedEvent(long firstSeq, long lastSeq, SseBaseEvent event, long bytes) {
    }

    private record Delivery(long seq, SseBaseEvent event) {
    }

    public static final class Subscription implements AutoCloseable {
        private final EventSink sink;
        private final BlockingQueue<Delivery> queue = new LinkedBlockingQueue<>(MAX_SUBSCRIBER_QUEUE);
        private final Thread thread;
        private volatile boolean closed;

        private Subscription(EventSink sink) {
            this.sink = sink;
            this.thread = Thread.ofVirtual().name("session-event-hub-subscriber").start(this::run);
        }

        private boolean offer(Delivery delivery) {
            if (closed) return false;
            if (queue.offer(delivery)) return true;
            LOGGER.warn("session event subscriber queue full, closing subscription");
            close();
            return false;
        }

        private void run() {
            try {
                while (!closed) {
                    var delivery = queue.take();
                    if (!sink.onEvent(delivery.seq, delivery.event)) {
                        close();
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            closed = true;
            queue.clear();
            thread.interrupt();
        }
    }
}
