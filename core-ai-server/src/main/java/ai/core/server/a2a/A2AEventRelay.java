package ai.core.server.a2a;

import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.TaskState;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Relays A2A stream events through Redis so any ingress Pod can proxy a stream
 * owned by another server Pod.
 *
 * @author xander
 */
public class A2AEventRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2AEventRelay.class);
    private static final String CHANNEL_PREFIX = "coreai:a2a:events:";

    private final JedisPool jedisPool;

    public A2AEventRelay(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public void publish(String taskId, StreamResponse event) {
        if (taskId == null || taskId.isBlank() || event == null) return;
        try (var jedis = jedisPool.getResource()) {
            jedis.publish(channel(taskId), JsonUtil.toJson(event));
        } catch (Exception e) {
            LOGGER.debug("failed to publish A2A stream event, taskId={}", taskId, e);
        }
    }

    public Subscription subscribe(String taskId, Consumer<StreamResponse> eventSender, Runnable closeStream) {
        var holder = new AtomicReference<JedisPubSub>();
        var subscribed = new CountDownLatch(1);
        var thread = Thread.ofVirtual()
                .name("a2a-event-relay-" + taskId)
                .start(() -> {
                    try (Jedis jedis = jedisPool.getResource()) {
                        var pubSub = new JedisPubSub() {
                            @Override
                            public void onMessage(String channel, String message) {
                                var event = JsonUtil.fromJson(StreamResponse.class, message);
                                eventSender.accept(event);
                                if (isTerminal(event)) {
                                    unsubscribe();
                                }
                            }

                            @Override
                            public void onSubscribe(String channel, int subscribedChannels) {
                                subscribed.countDown();
                            }
                        };
                        holder.set(pubSub);
                        jedis.subscribe(pubSub, channel(taskId));
                    } catch (Exception e) {
                        LOGGER.debug("A2A event relay stopped, taskId={}", taskId, e);
                    } finally {
                        if (closeStream != null) closeStream.run();
                    }
                });
        awaitSubscribed(taskId, subscribed);
        return () -> {
            var pubSub = holder.get();
            if (pubSub != null) {
                try {
                    pubSub.unsubscribe();
                } catch (Exception e) {
                    LOGGER.debug("failed to unsubscribe A2A event relay, taskId={}", taskId, e);
                }
            }
            thread.interrupt();
        };
    }

    private void awaitSubscribed(String taskId, CountDownLatch subscribed) {
        try {
            if (!subscribed.await(2, TimeUnit.SECONDS)) {
                LOGGER.warn("timed out waiting for A2A event relay subscription, taskId={}", taskId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("interrupted waiting for A2A event relay subscription, taskId={}", taskId);
        }
    }

    private boolean isTerminal(StreamResponse event) {
        if (event.statusUpdate == null || event.statusUpdate.status == null) return false;
        var state = event.statusUpdate.status.state;
        return state == TaskState.COMPLETED
                || state == TaskState.FAILED
                || state == TaskState.CANCELED
                || state == TaskState.REJECTED
                || state == TaskState.INPUT_REQUIRED
                || state == TaskState.AUTH_REQUIRED;
    }

    private String channel(String taskId) {
        return CHANNEL_PREFIX + taskId;
    }

    public interface Subscription {
        void close();
    }
}
