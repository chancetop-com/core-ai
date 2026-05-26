package ai.core.server.web.sse;

import ai.core.api.server.session.EventType;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.sse.SseTurnCompleteEvent;
import core.framework.inject.Inject;
import core.framework.web.sse.Channel;

import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
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

        var isTurnComplete = sseEvent instanceof SseTurnCompleteEvent;
        synchronized (state) {
            if (state.eventBuffer.size() >= MAX_BUFFER_SIZE) {
                state.eventBuffer.removeFirst();
                if (!state.overflowLogged) {
                    state.overflowLogged = true;
                    LOGGER.warn("session event buffer overflow, oldest events will be lost on replay, sessionId={}, capacity={}", sessionId, MAX_BUFFER_SIZE);
                }
            }
            state.eventBuffer.addLast(sseEvent);
            channelService.send(sessionId, sseEvent);
            if (isTurnComplete) {
                // turn finished — persisted message is now in history, no need to replay further
                state.eventBuffer.clear();
                state.overflowLogged = false;
            }
        }
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
        }
    }

    public List<SseBaseEvent> getEventBuffer(String sessionId) {
        var state = stateMap.get(sessionId);
        if (state == null) return List.of();
        synchronized (state) {
            return List.copyOf(state.eventBuffer);
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
    }
}
