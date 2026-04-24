package ai.core.server.messaging;

import ai.core.api.server.session.sse.SseBaseEvent;
import core.framework.json.JSON;
import redis.clients.jedis.JedisPool;

/**
 * @author stephen
 */
public class EventPublisher {
    private static final String CHANNEL_PREFIX = "coreai:sse:";

    private final JedisPool jedisPool;

    public EventPublisher(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public void publish(String sessionId, SseBaseEvent event) {
        // Initialize event fields (sessionId, timestamp, type)
        SseEventHelper.initEvent(event, sessionId);

        // Serialize: className + newline + JSON
        var className = event.getClass().getSimpleName();
        var json = JSON.toJSON(event);
        var message = className + "\n" + json;

        try (var jedis = jedisPool.getResource()) {
            var channel = CHANNEL_PREFIX + sessionId;
            jedis.publish(channel, message);
        }
    }
}
