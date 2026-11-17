package ai.core.server.messaging;

import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.server.web.sse.SessionEventHubRegistry;
import ai.core.utils.JsonUtil;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * @author stephen
 */
public class EventPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventPublisher.class);
    private static final String CHANNEL_PREFIX = "coreai:sse:";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final JedisPool jedisPool;
    private final SseDeliveryMode mode;
    private final SessionEventHubRegistry hubRegistry;
    private final SessionOwnershipRegistry ownershipRegistry;
    private final PodRegistry podRegistry;
    private final String token;

    public EventPublisher(JedisPool jedisPool) {
        this(jedisPool, SseDeliveryMode.REDIS, null, null, null, "");
    }

    public EventPublisher(JedisPool jedisPool, SseDeliveryMode mode, SessionEventHubRegistry hubRegistry,
                          SessionOwnershipRegistry ownershipRegistry, PodRegistry podRegistry, String token) {
        this.jedisPool = jedisPool;
        this.mode = mode;
        this.hubRegistry = hubRegistry;
        this.ownershipRegistry = ownershipRegistry;
        this.podRegistry = podRegistry;
        this.token = token;
    }

    public void publish(String sessionId, SseBaseEvent event) {
        SseEventHelper.initEvent(event, sessionId);

        if (mode == SseDeliveryMode.DIRECTED) {
            var hub = hubRegistry.get(sessionId);
            if (hub == null && ownershipRegistry != null && ownershipRegistry.isOwner(sessionId)) {
                hub = hubRegistry.getOrCreate(sessionId);
            }
            if (hub != null) {
                hub.publish(event);
            } else {
                forwardToOwner(sessionId, event);
            }
        }

        publishRedis(sessionId, event);
    }

    private void publishRedis(String sessionId, SseBaseEvent event) {
        var className = event.getClass().getSimpleName();
        var json = JSON.toJSON(event);
        var message = className + "\n" + json;
        try (var jedis = jedisPool.getResource()) {
            jedis.publish(CHANNEL_PREFIX + sessionId, message);
        } catch (RuntimeException e) {
            // The local hub already has the event; a Redis hiccup must not fail the turn.
            LOGGER.warn("failed to publish session event to redis, sessionId={}", sessionId, e);
        }
    }

    private void forwardToOwner(String sessionId, SseBaseEvent event) {
        if (ownershipRegistry == null || podRegistry == null || token == null || token.isBlank()) return;
        var owner = ownershipRegistry.getOwner(sessionId);
        if (owner == null || owner.isBlank()) return;
        var address = podRegistry.address(owner);
        if (address == null || address.isBlank()) return;
        try {
            var body = JsonUtil.toJson(new InternalSseEvent(sessionId, SseEventCodec.toJson(event)));
            var request = HttpRequest.newBuilder(URI.create("http://" + address + "/_internal/sessions/events"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                LOGGER.warn("failed to forward session event to owner, sessionId={}, owner={}, status={}",
                        sessionId, owner, response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("failed to forward session event to owner, sessionId={}, owner={}", sessionId, owner, e);
        }
    }
}
