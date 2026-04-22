package ai.core.server.web.sse;

import ai.core.api.server.session.EventType;
import ai.core.api.server.session.sse.SseBaseEvent;
import core.framework.inject.Inject;
import core.framework.web.sse.Channel;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class SessionChannelService {
    @Inject
    ChannelService channelService;

    private final Map<String, SessionChannelState> stateMap = new ConcurrentHashMap<>();

    public void connect(Channel<SseBaseEvent> channel, String sessionId) {
        channelService.connect(channel, sessionId);
        stateMap.put(sessionId, new SessionChannelState(sessionId));
    }

    public void send(String sessionId, SseBaseEvent sseEvent) {
        var state = stateMap.get(sessionId);
        if (state == null) return;

        sseEvent.sessionId = sessionId;
        sseEvent.timestamp = ZonedDateTime.now();
        setEventType(sseEvent);
        channelService.send(sessionId, sseEvent);
        state.eventBuffer.add(sseEvent);
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
        } else if (sseEvent instanceof ai.core.api.server.session.sse.SseTurnCompleteEvent) {
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
        return List.copyOf(state.eventBuffer);
    }

    public void close(String sessionId) {
        stateMap.remove(sessionId);
        channelService.close(sessionId);
    }

    private static class SessionChannelState {
        final String sessionId;
        final List<SseBaseEvent> eventBuffer = new ArrayList<>();

        SessionChannelState(String sessionId) {
            this.sessionId = sessionId;
        }
    }
}
