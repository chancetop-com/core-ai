package ai.core.server.web.sse;

import ai.core.api.server.session.sse.SseBaseEvent;
import core.framework.web.sse.Channel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class ChannelService {
    private final Map<String, Channel<SseBaseEvent>> channelMap = new ConcurrentHashMap<>();

    public void connect(Channel<SseBaseEvent> channel, String sessionId) {
        var old = channelMap.put(sessionId, channel);
        if (old != null) {
            old.close();
        }
    }

    public void send(String sessionId, SseBaseEvent event) {
        var channel = channelMap.get(sessionId);
        if (channel != null) {
            channel.send(event);
        }
    }

    public void close(String sessionId) {
        var channel = channelMap.remove(sessionId);
        if (channel != null) {
            channel.close();
        }
    }

    public boolean closeIfCurrent(String sessionId, Channel<SseBaseEvent> channel) {
        if (channelMap.remove(sessionId, channel)) {
            channel.close();
            return true;
        }
        return false;
    }
}
