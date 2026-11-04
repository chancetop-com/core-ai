package ai.core.server.web.sse;

import ai.core.api.server.session.EventType;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.sse.SseBatchToolStartEvent;
import ai.core.api.server.session.sse.SseCompressionEvent;
import ai.core.api.server.session.sse.SseCustomEvent;
import ai.core.api.server.session.sse.SseEnvironmentOutputChunkEvent;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SsePlanUpdateEvent;
import ai.core.api.server.session.sse.SseReasoningChunkEvent;
import ai.core.api.server.session.sse.SseSandboxEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.api.server.session.sse.SseTaskStatusEvent;
import ai.core.api.server.session.sse.SseTextChunkEvent;
import ai.core.api.server.session.sse.SseToolApprovalRequestEvent;
import ai.core.api.server.session.sse.SseToolResultEvent;
import ai.core.api.server.session.sse.SseToolStartEvent;
import ai.core.api.server.session.sse.SseTurnCompleteEvent;
import core.framework.inject.Inject;
import core.framework.web.sse.Channel;

import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToLongFunction;

/**
 * @author stephen
 */
public class SessionChannelService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SessionChannelService.class);
    private static final int MAX_BUFFER_SIZE = 50000;
    /** Per-session replay budget. A reconnect replays this buffer, so it must not pin an unbounded turn. */
    static final long DEFAULT_MAX_BUFFER_BYTES = 8L * 1024 * 1024;
    private static final int EVENT_OVERHEAD_BYTES = 256;
    private static final int STRING_OVERHEAD_BYTES = 40;

    /** Rough heap estimate: enough to bound the buffer, cheap enough to run on every chunk. */
    private static long estimatedBytes(SseBaseEvent event) {
        long size = EVENT_OVERHEAD_BYTES;
        if (event instanceof SseTextChunkEvent chunk) return size + stringBytes(chunk.content);
        if (event instanceof SseReasoningChunkEvent chunk) return size + stringBytes(chunk.content);
        if (event instanceof SseToolResultEvent result) return size + stringBytes(result.result);
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
                for (var todo : plan.todos) {
                    size += stringBytes(todo.content);
                }
            }
            return size;
        }
        if (event instanceof SseTurnCompleteEvent complete) return size + stringBytes(complete.output);
        return size;
    }

    private static long chunkBytes(SseBaseEvent event) {
        // merged into an already-counted event: only the appended text is new
        if (event instanceof SseTextChunkEvent chunk) return chunk.content == null ? 0 : 2L * chunk.content.length();
        if (event instanceof SseReasoningChunkEvent chunk) return chunk.content == null ? 0 : 2L * chunk.content.length();
        return 0;
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
            for (var item : values) {
                size += valueBytes(item);
            }
            return size;
        }
        if (value instanceof Number || value instanceof Boolean) return 16;
        return 64;
    }

    private static long stringBytes(String value) {
        return value == null ? 0 : STRING_OVERHEAD_BYTES + 2L * value.length();
    }

    @Inject
    ChannelService channelService;

    private final Map<String, SessionChannelState> stateMap = new ConcurrentHashMap<>();
    private final AtomicLong droppedEvents = new AtomicLong();
    // visible for tests; production keeps the default
    volatile long maxBufferBytes = DEFAULT_MAX_BUFFER_BYTES;

    /**
     * Returns true if a local session state exists for the given session ID.
     * This indicates that an SSE client is (or was recently) connected to this JVM,
     * allowing in-process event delivery to bypass Redis pub/sub.
     */
    public boolean hasSession(String sessionId) {
        return stateMap.containsKey(sessionId);
    }

    public void connect(Channel<SseBaseEvent> channel, String sessionId) {
        var state = stateMap.computeIfAbsent(sessionId, k -> new SessionChannelState());

        synchronized (state) {
            channelService.connect(channel, sessionId);
            for (var event : state.eventBuffer) {
                channelService.send(sessionId, event);
            }
        }
    }

    public void send(String sessionId, SseBaseEvent sseEvent) {
        var state = stateMap.get(sessionId);
        if (state == null) return;

        sseEvent.sessionId = sessionId;
        sseEvent.timestamp = ZonedDateTime.now();
        setEventType(sseEvent);

        // A task-status ping is about work that outlives the turn that started it, so it arrives exactly
        // when the buffer is in its post-terminal state and would otherwise be dropped as a late event.
        // It is sent straight through: buffering it would replay a stale "task finished" on reconnect.
        if (sseEvent instanceof SseTaskStatusEvent) {
            channelService.send(sessionId, sseEvent);
            return;
        }

        var isRunningStatus = sseEvent instanceof SseStatusChangeEvent statusChange
                && statusChange.status == SessionStatus.RUNNING;
        var isErrorStatus = sseEvent instanceof SseStatusChangeEvent statusChange
                && statusChange.status == SessionStatus.ERROR;
        var isTerminalEvent = sseEvent instanceof SseTurnCompleteEvent
                || sseEvent instanceof SseErrorEvent
                || sseEvent instanceof SseStatusChangeEvent statusChange
                && statusChange.status != SessionStatus.RUNNING;
        synchronized (state) {
            if (isRunningStatus) {
                resetBuffer(state);
                state.replayTerminated = false;
            } else if (isTerminalEvent) {
                // Keep the terminal event itself for status/reconnect, but drop all
                // previously buffered stream chunks so cancelled/completed turns do not replay.
                if (isErrorStatus) {
                    state.eventBuffer.removeIf(event -> !(event instanceof SseErrorEvent));
                    state.bufferedBytes = estimateBufferBytes(state.eventBuffer);
                } else {
                    resetBuffer(state);
                }
                state.replayTerminated = true;
            } else if (state.replayTerminated && isTurnActivityEvent(sseEvent)) {
                LOGGER.debug("dropping late session event after terminal state, sessionId={}, event={}",
                        sessionId, sseEvent.getClass().getSimpleName());
                return;
            }
            bufferEvent(state, sessionId, sseEvent);
        }
    }

    /**
     * Appends the event to the replay buffer, dropping the oldest one when the buffer is full, and
     * forwards it to the channel. Consecutive streaming chunks are merged into the last buffered
     * event first, so a long turn does not consume a buffer slot per token.
     */
    private void bufferEvent(SessionChannelState state, String sessionId, SseBaseEvent sseEvent) {
        if (!state.eventBuffer.isEmpty() && mergeConsecutiveChunk(state.eventBuffer.peekLast(), sseEvent)) {
            state.bufferedBytes += chunkBytes(sseEvent);
            // a single very long turn can outgrow the budget through merging alone
            trimToBudget(state, sessionId);
            channelService.send(sessionId, sseEvent);
            return;
        }
        long eventBytes = estimatedBytes(sseEvent);
        if (eventBytes > maxBufferBytes) {
            // one tool result must not evict the whole turn's replay, and must not exceed the budget itself
            recordDrop(state, sessionId, "single event too large");
            channelService.send(sessionId, sseEvent);
            return;
        }
        while (!state.eventBuffer.isEmpty()
                && (state.eventBuffer.size() >= MAX_BUFFER_SIZE || state.bufferedBytes + eventBytes > maxBufferBytes)) {
            var removed = state.eventBuffer.removeFirst();
            state.bufferedBytes -= estimatedBytes(removed);
            recordDrop(state, sessionId, "buffer full");
        }
        state.eventBuffer.addLast(sseEvent);
        state.bufferedBytes += eventBytes;
        channelService.send(sessionId, sseEvent);
    }

    private void trimToBudget(SessionChannelState state, String sessionId) {
        while (state.bufferedBytes > maxBufferBytes && !state.eventBuffer.isEmpty()) {
            var removed = state.eventBuffer.removeFirst();
            state.bufferedBytes -= estimatedBytes(removed);
            recordDrop(state, sessionId, "buffer full");
        }
    }

    private void resetBuffer(SessionChannelState state) {
        state.eventBuffer.clear();
        state.bufferedBytes = 0;
        state.overflowLogged = false;
    }

    private void recordDrop(SessionChannelState state, String sessionId, String reason) {
        droppedEvents.incrementAndGet();
        if (!state.overflowLogged) {
            state.overflowLogged = true;
            LOGGER.warn("session event buffer overflow, oldest events will be lost on replay, sessionId={}, capacityEvents={}, capacityBytes={}, reason={}",
                    sessionId, MAX_BUFFER_SIZE, maxBufferBytes, reason);
        }
    }

    private long estimateBufferBytes(Deque<SseBaseEvent> events) {
        long total = 0;
        for (var event : events) {
            total += estimatedBytes(event);
        }
        return total;
    }

    private boolean isTurnActivityEvent(SseBaseEvent sseEvent) {
        return !(sseEvent instanceof SseStatusChangeEvent) && !(sseEvent instanceof SseTurnCompleteEvent) && !(sseEvent instanceof SseErrorEvent);
    }

    /**
     * Merge a streaming chunk event into the previous event of the same type when they are
     * consecutive in the buffer. This prevents a rapid stream of small text/reasoning chunks
     * from consuming an excessive number of buffer slots.
     *
     * @return true if the new event was merged into the last event (caller should skip adding)
     */
    private boolean mergeConsecutiveChunk(SseBaseEvent last, SseBaseEvent incoming) {
        if (last instanceof SseTextChunkEvent lastText && incoming instanceof SseTextChunkEvent incomingText) {
            lastText.content += incomingText.content;
            lastText.isFinalChunk = incomingText.isFinalChunk;
            lastText.timestamp = incomingText.timestamp;
            return true;
        }
        if (last instanceof SseReasoningChunkEvent lastReasoning && incoming instanceof SseReasoningChunkEvent incomingReasoning) {
            lastReasoning.content += incomingReasoning.content;
            lastReasoning.isFinalChunk = incomingReasoning.isFinalChunk;
            lastReasoning.timestamp = incomingReasoning.timestamp;
            return true;
        }
        return false;
    }

    private void setEventType(SseBaseEvent sseEvent) {
        if (sseEvent instanceof SseTextChunkEvent) {
            sseEvent.type = EventType.TEXT_CHUNK;
        } else if (sseEvent instanceof SseReasoningChunkEvent) {
            sseEvent.type = EventType.REASONING_CHUNK;
        } else if (sseEvent instanceof SseToolStartEvent) {
            sseEvent.type = EventType.TOOL_START;
        } else if (sseEvent instanceof SseToolResultEvent) {
            sseEvent.type = EventType.TOOL_RESULT;
        } else if (sseEvent instanceof SsePlanUpdateEvent) {
            sseEvent.type = EventType.PLAN_UPDATE;
        } else if (sseEvent instanceof SseToolApprovalRequestEvent) {
            sseEvent.type = EventType.TOOL_APPROVAL_REQUEST;
        } else if (sseEvent instanceof SseTurnCompleteEvent) {
            sseEvent.type = EventType.TURN_COMPLETE;
        } else if (sseEvent instanceof SseErrorEvent) {
            sseEvent.type = EventType.ERROR;
        } else if (sseEvent instanceof SseStatusChangeEvent) {
            sseEvent.type = EventType.STATUS_CHANGE;
        } else if (sseEvent instanceof SseCompressionEvent) {
            sseEvent.type = EventType.COMPRESSION;
        } else if (sseEvent instanceof SseSandboxEvent) {
            sseEvent.type = EventType.SANDBOX;
        } else if (sseEvent instanceof SseBatchToolStartEvent) {
            sseEvent.type = EventType.BATCH_TOOL_START;
        } else if (sseEvent instanceof SseEnvironmentOutputChunkEvent) {
            sseEvent.type = EventType.ENVIRONMENT_OUTPUT_CHUNK;
        } else if (sseEvent instanceof SseTaskStatusEvent) {
            sseEvent.type = EventType.TASK_STATUS;
        } else if (sseEvent instanceof SseCustomEvent) {
            sseEvent.type = EventType.CUSTOM;
        }
    }

    public List<SseBaseEvent> getEventBuffer(String sessionId) {
        var state = stateMap.get(sessionId);
        if (state == null) return List.of();
        synchronized (state) {
            return List.copyOf(state.eventBuffer);
        }
    }

    /** Heap currently held by replay buffers on this pod. */
    public long bufferedBytes() {
        return total(state -> state.bufferedBytes);
    }

    public long bufferedEvents() {
        return total(state -> state.eventBuffer.size());
    }

    /** Events that were sent live but not retained for replay because the buffer budget was exhausted. */
    public long droppedTotal() {
        return droppedEvents.get();
    }

    private long total(ToLongFunction<SessionChannelState> reader) {
        long total = 0;
        for (var state : stateMap.values()) {
            synchronized (state) {
                total += reader.applyAsLong(state);
            }
        }
        return total;
    }

    public SessionStatus status(String sessionId) {
        var state = stateMap.get(sessionId);
        if (state == null) return SessionStatus.IDLE;
        synchronized (state) {
            if (state.eventBuffer.isEmpty()) return SessionStatus.IDLE;
            Iterator<SseBaseEvent> it = state.eventBuffer.descendingIterator();
            while (it.hasNext()) {
                var event = it.next();
                if (event instanceof SseStatusChangeEvent statusChange && statusChange.status != null) {
                    return statusChange.status;
                }
                if (event instanceof SseTurnCompleteEvent) return SessionStatus.IDLE;
                if (event instanceof SseErrorEvent) return SessionStatus.ERROR;
            }
            return SessionStatus.RUNNING;
        }
    }

    public void close(String sessionId) {
        stateMap.remove(sessionId);
        channelService.close(sessionId);
    }

    public void closeIfCurrent(String sessionId, Channel<SseBaseEvent> channel) {
        // intentionally do NOT remove stateMap here: buffer must survive client disconnects
        // so a returning user can replay the in-progress turn. Lifecycle cleanup happens in close().
        channelService.closeIfCurrent(sessionId, channel);
    }

    private static final class SessionChannelState {
        final Deque<SseBaseEvent> eventBuffer = new ArrayDeque<>();
        long bufferedBytes;
        boolean overflowLogged;
        boolean replayTerminated;
    }
}
