package ai.core.server.web.sse;

import ai.core.api.server.session.EventType;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.api.server.session.sse.SseTurnCompleteEvent;
import core.framework.inject.Inject;
import core.framework.web.sse.Channel;

import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class SessionChannelService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SessionChannelService.class);
    private static final int MAX_BUFFER_SIZE = 5000;

    @Inject
    ChannelService channelService;

    private final Map<String, SessionChannelState> stateMap = new ConcurrentHashMap<>();

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
                state.eventBuffer.clear();
                state.overflowLogged = false;
                state.replayTerminated = false;
            } else if (isTerminalEvent) {
                // Keep the terminal event itself for status/reconnect, but drop all
                // previously buffered stream chunks so cancelled/completed turns do not replay.
                if (isErrorStatus) {
                    state.eventBuffer.removeIf(event -> !(event instanceof SseErrorEvent));
                } else {
                    state.eventBuffer.clear();
                }
                state.overflowLogged = false;
                state.replayTerminated = true;
            } else if (state.replayTerminated && isTurnActivityEvent(sseEvent)) {
                LOGGER.debug("dropping late session event after terminal state, sessionId={}, event={}",
                        sessionId, sseEvent.getClass().getSimpleName());
                return;
            }
            if (state.eventBuffer.size() >= MAX_BUFFER_SIZE) {
                state.eventBuffer.removeFirst();
                if (!state.overflowLogged) {
                    state.overflowLogged = true;
                    LOGGER.warn("session event buffer overflow, oldest events will be lost on replay, sessionId={}, capacity={}", sessionId, MAX_BUFFER_SIZE);
                }
            }
            state.eventBuffer.addLast(sseEvent);
            channelService.send(sessionId, sseEvent);
        }
    }

    private boolean isTurnActivityEvent(SseBaseEvent sseEvent) {
        return !(sseEvent instanceof SseStatusChangeEvent) && !(sseEvent instanceof SseTurnCompleteEvent) && !(sseEvent instanceof SseErrorEvent);
    }

    private void setEventType(SseBaseEvent sseEvent) {
        if (sseEvent instanceof ai.core.api.server.session.sse.SseTextChunkEvent) {
            sseEvent.type = EventType.TEXT_CHUNK;
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SseReasoningChunkEvent) {
            sseEvent.type = EventType.REASONING_CHUNK;
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SseToolStartEvent) {
            sseEvent.type = EventType.TOOL_START;
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SseToolResultEvent) {
            sseEvent.type = EventType.TOOL_RESULT;
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SsePlanUpdateEvent) {
            sseEvent.type = EventType.PLAN_UPDATE;
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SseToolApprovalRequestEvent) {
            sseEvent.type = EventType.TOOL_APPROVAL_REQUEST;
        } else if (sseEvent instanceof SseTurnCompleteEvent) {
            sseEvent.type = EventType.TURN_COMPLETE;
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SseErrorEvent) {
            sseEvent.type = EventType.ERROR;
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SseStatusChangeEvent) {
            sseEvent.type = EventType.STATUS_CHANGE;
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SseCompressionEvent) {
            sseEvent.type = EventType.COMPRESSION;
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SseSandboxEvent) {
            sseEvent.type = EventType.SANDBOX;
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SseBatchToolStartEvent) {
            sseEvent.type = EventType.BATCH_TOOL_START;
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SseEnvironmentOutputChunkEvent) {
            sseEvent.type = EventType.ENVIRONMENT_OUTPUT_CHUNK;
        }
    }

    public List<SseBaseEvent> getEventBuffer(String sessionId) {
        var state = stateMap.get(sessionId);
        if (state == null) return List.of();
        synchronized (state) {
            return List.copyOf(state.eventBuffer);
        }
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
        boolean overflowLogged;
        boolean replayTerminated;
    }
}
