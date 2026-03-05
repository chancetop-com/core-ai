package ai.core.server.web.sse;

import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.EventType;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.sse.SseStartEvent;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.web.sse.Channel;

import java.util.ArrayList;
import java.util.Collections;
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

    public void send(String sessionId, EventType type, AgentEvent event) {
        var state = stateMap.get(sessionId);
        if (state == null) return;

        var sse = new SseStartEvent();
        sse.type = type;
        sse.sessionId = sessionId;
        sse.data = JSON.toJSON(event);
        channelService.send(sessionId, sse);
        state.eventBuffer.add(sse);
    }

    public List<SseStartEvent> getEventBuffer(String sessionId) {
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
        final List<SseStartEvent> eventBuffer = Collections.synchronizedList(new ArrayList<>());

        SessionChannelState(String sessionId) {
            this.sessionId = sessionId;
        }
    }
}
