package ai.core.server.messaging;

import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.web.sse.SessionChannelService;
import core.framework.json.JSON;
import redis.clients.jedis.JedisPool;

/**
 * @author stephen
 */
public class EventPublisher {
    private static final String CHANNEL_PREFIX = "coreai:sse:";

    private final JedisPool jedisPool;
    private SessionChannelService sessionChannelService;

    public EventPublisher(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    /**
     * Set a local {@link SessionChannelService} for in-process delivery.
     * When set, events for sessions with a local SSE channel are delivered directly
     * without going through Redis pub/sub, eliminating serialization, deserialization,
     * and network round-trip overhead for same-JVM delivery.
     */
    public void setSessionChannelService(SessionChannelService sessionChannelService) {
        this.sessionChannelService = sessionChannelService;
    }

    public void publish(String sessionId, SseBaseEvent event) {
        // Initialize event fields (sessionId, timestamp, type)
        SseEventHelper.initEvent(event, sessionId);

        // In-process direct delivery: when the SSE client is connected to the same JVM,
        // skip Redis pub/sub entirely. This avoids serialization, deserialization, and
        // Redis round-trip (~12ms/event) for ~80% of the event processing time.
        if (sessionChannelService != null && sessionChannelService.hasSession(sessionId)) {
            sessionChannelService.send(sessionId, event);
            return;
        }

        // Cross-pod fallback: publish to Redis for other pods to consume
        var className = event.getClass().getSimpleName();
        var json = JSON.toJSON(event);
        var message = className + "\n" + json;

        try (var jedis = jedisPool.getResource()) {
            var channel = CHANNEL_PREFIX + sessionId;
            jedis.publish(channel, message);
        }
    }
}
