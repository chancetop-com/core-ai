package ai.core.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

/**
 * Subscribes to {@code coreai:rpc:*} Redis Pub/Sub channels and dispatches
 * responses to the matching pending {@link RpcClient} call.
 * <p>
 * This is a singleton — one subscriber handles all RPC responses for this Pod.
 *
 * @author stephen
 */
public class RpcResponseSubscriber {
    private static final Logger LOGGER = LoggerFactory.getLogger(RpcResponseSubscriber.class);
    private static final String RPC_CHANNEL_PATTERN = "coreai:rpc:*";

    private final JedisPool jedisPool;
    private final RpcClient rpcClient;

    private volatile boolean running = true;
    private Thread subscriberThread;

    public RpcResponseSubscriber(JedisPool jedisPool, RpcClient rpcClient) {
        this.jedisPool = jedisPool;
        this.rpcClient = rpcClient;
    }

    public void start() {
        subscriberThread = Thread.ofVirtual()
                .name("rpc-response-subscriber")
                .start(this::subscribeLoop);
        LOGGER.info("RpcResponseSubscriber started, subscribing to {}", RPC_CHANNEL_PATTERN);
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
                        handleResponse(channel, message);
                    }
                };
                // Blocks until unsubscribe or connection loss
                jedis.psubscribe(pubSub, RPC_CHANNEL_PATTERN);
            } catch (Exception e) {
                if (running) {
                    LOGGER.warn("RpcResponseSubscriber connection lost, reconnecting in 3s...", e);
                    sleepBeforeReconnect();
                }
            }
        }
    }

    private void handleResponse(String channel, String message) {
        try {
            // Extract requestId from channel: "coreai:rpc:{requestId}"
            var requestId = channel.substring("coreai:rpc:".length());
            if (requestId.isEmpty()) {
                LOGGER.warn("invalid RPC response channel: {}", channel);
                return;
            }
            rpcClient.onResponse(requestId, message);
        } catch (Exception e) {
            LOGGER.warn("failed to handle RPC response, channel={}", channel, e);
        }
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
