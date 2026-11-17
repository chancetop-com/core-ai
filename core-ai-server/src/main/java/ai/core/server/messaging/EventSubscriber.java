package ai.core.server.messaging;

import ai.core.server.web.sse.SessionChannelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

/**
 * @author stephen
 */
public class EventSubscriber {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventSubscriber.class);
    private static final String CHANNEL_PATTERN = "coreai:sse:*";

    private final JedisPool jedisPool;
    private final SessionChannelService sessionChannelService;
    private volatile boolean running = true;
    private Thread subscriberThread;

    public EventSubscriber(JedisPool jedisPool, SessionChannelService sessionChannelService) {
        this.jedisPool = jedisPool;
        this.sessionChannelService = sessionChannelService;
    }

    public void start() {
        subscriberThread = Thread.ofVirtual()
                .name("event-subscriber")
                .start(this::subscribeLoop);
        LOGGER.info("EventSubscriber started, subscribing to {}", CHANNEL_PATTERN);
    }

    public void stop() {
        running = false;
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
    }

    private void subscribeLoop() {
        while (running) {
            try (Jedis jedis = jedisPool.getResource()) {
                var pubSub = new JedisPubSub() {
                    @Override
                    public void onPMessage(String pattern, String channel, String message) {
                        handleEvent(channel, message);
                    }
                };
                // Subscribe blocks until unsubscribe or connection loss
                jedis.psubscribe(pubSub, CHANNEL_PATTERN);
            } catch (Exception e) {
                if (running) {
                    LOGGER.warn("EventSubscriber connection lost, reconnecting in 3s...", e);
                    sleepBeforeReconnect();
                }
            }
        }
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleEvent(String channel, String message) {
        try {
            // Extract sessionId from channel name: "coreai:sse:{sessionId}"
            var sessionId = channel.substring("coreai:sse:".length());
            if (sessionId.isEmpty()) return;

            // A healthy directed relay owns this session; skip JSON parsing entirely.
            if (sessionChannelService.hasHealthyRelay(sessionId)) return;

            // Message format: "SseTextChunkEvent\n{...json...}"
            var newlineIdx = message.indexOf('\n');
            if (newlineIdx <= 0) {
                LOGGER.warn("invalid event message format, channel={}", channel);
                return;
            }

            var json = message.substring(newlineIdx + 1);
            var event = SseEventCodec.fromJson(json);
            if (event == null) {
                LOGGER.warn("unknown event payload, channel={}", channel);
                return;
            }

            sessionChannelService.onBroadcast(sessionId, event);
        } catch (Exception e) {
            LOGGER.warn("failed to handle event, channel={}", channel, e);
        }
    }
}
